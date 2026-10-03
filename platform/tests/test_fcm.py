import base64
import io
import json
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest.mock import patch

from server import AccountStore, FcmPusher, NinePlusAdapter, PushDeviceStore, Settings


class PushDeviceStoreTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.settings = Settings("", ninebot_config_dir=Path(self.temp.name) / "ninebot")

    def test_registration_persists_explicit_platform_and_isolates_accounts(self):
        store = PushDeviceStore(self.settings)
        token = "fcm-token-with-colon:segment"
        result = store.register(token, "alice", "app.example", "development", "android")
        self.assertEqual(result["platform"], "android")
        reloaded = PushDeviceStore(self.settings)
        self.assertEqual(reloaded.devices_for("alice")[0]["token"], token)
        self.assertEqual(reloaded.devices_for("bob"), [])

    def test_unregistration_requires_owning_account(self):
        store = PushDeviceStore(self.settings)
        store.register("device-token", "alice", platform="ios")
        self.assertFalse(store.remove("device-token", "bob"))
        self.assertEqual(len(store.devices_for("alice")), 1)
        self.assertTrue(store.remove("device-token", "alice"))
        self.assertEqual(store.devices_for("alice"), [])

    def test_device_file_is_private(self):
        store = PushDeviceStore(self.settings)
        store.register("device-token", "alice", platform="ios")
        mode = store.path.stat().st_mode & 0o777
        self.assertEqual(mode, 0o600)


class FcmPusherTests(unittest.TestCase):
    SERVICE_ACCOUNT = {
        "type": "service_account",
        "project_id": "firebase-test-project",
        "private_key_id": "key-id",
        "private_key": "private-key-material",
        "client_email": "sender@example.invalid",
        "token_uri": "https://oauth2.googleapis.com/token",
    }

    class Credentials:
        def __init__(self):
            self.token = None
            self.valid = False
            self.expiry = None
            self.refresh_count = 0

        def refresh(self, request):
            self.refresh_count += 1
            self.token = "oauth-access-token"
            self.valid = True

    class Response:
        status = 200

        def __init__(self, payload):
            self.payload = payload

        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return False

        def read(self):
            return self.payload

    def make_settings(self):
        return Settings(
            "",
            fcm_service_account_json=json.dumps(self.SERVICE_ACCOUNT),
            fcm_project_id="firebase-test-project",
        )

    def test_http_v1_sends_account_scoped_notification_and_data(self):
        credentials = self.Credentials()
        captured = {}

        def load_credentials(info, scopes):
            captured["info"] = info
            captured["scopes"] = scopes
            return credentials

        def open_request(request, timeout):
            captured["request"] = request
            captured["timeout"] = timeout
            return self.Response(b'{"name":"projects/firebase-test-project/messages/1"}')

        pusher = FcmPusher(
            self.make_settings(),
            credentials_factory=load_credentials,
            auth_request_factory=lambda: (lambda *_args, **_kwargs: None),
            urlopen=open_request,
        )
        result = pusher.send("registration-token", "Title", "Body", {"vehicle_sn": "SN1"})

        request = captured["request"]
        message = json.loads(request.data)["message"]
        self.assertTrue(result["sent"])
        self.assertEqual(result["mode"], "v1")
        self.assertEqual(request.full_url, "https://fcm.googleapis.com/v1/projects/firebase-test-project/messages:send")
        self.assertEqual(request.get_header("Authorization"), "Bearer oauth-access-token")
        self.assertEqual(message["token"], "registration-token")
        self.assertEqual(message["notification"], {"title": "Title", "body": "Body"})
        self.assertEqual(message["data"], {"vehicle_sn": "SN1"})
        self.assertEqual(captured["scopes"], [FcmPusher.SCOPE])
        self.assertNotIn("private-key-material", repr(result))

    def test_fcm_failure_does_not_return_provider_body_or_token(self):
        token = "registration-token-secret"

        def failed_request(request, timeout):
            raise urllib.error.HTTPError(
                request.full_url, 403, "denied", {},
                io.BytesIO(b'{"error":{"status":"SENDER_ID_MISMATCH","message":"registration-token-secret"}}'),
            )

        pusher = FcmPusher(
            self.make_settings(),
            credentials_factory=lambda _info, scopes: self.Credentials(),
            auth_request_factory=lambda: (lambda *_args, **_kwargs: None),
            urlopen=failed_request,
        )
        result = pusher.send(token, "Title", "Body")
        self.assertFalse(result["sent"])
        self.assertEqual(result["status"], 403)
        self.assertEqual(result["reason"], "SENDER_ID_MISMATCH")
        self.assertNotIn(token, repr(result))

    def test_service_account_base64_configuration_is_parsed_without_logging_secret(self):
        encoded = base64.b64encode(json.dumps(self.SERVICE_ACCOUNT).encode()).decode()
        with patch.dict("os.environ", {
            "FCM_SERVICE_ACCOUNT_B64": encoded,
            "FCM_PROJECT_ID": "",
        }, clear=True):
            settings = Settings.from_env()
        self.assertEqual(json.loads(settings.fcm_service_account_json)["client_email"], "sender@example.invalid")
        self.assertEqual(settings.fcm_project_id, "firebase-test-project")

    def test_service_account_file_configuration_is_read_from_local_path(self):
        with tempfile.TemporaryDirectory() as directory:
            key_path = Path(directory) / "service-account.json"
            key_path.write_text(json.dumps(self.SERVICE_ACCOUNT), encoding="utf-8")
            with patch.dict("os.environ", {"FCM_SERVICE_ACCOUNT_PATH": str(key_path)}, clear=True):
                settings = Settings.from_env()
            self.assertEqual(settings.fcm_project_id, "firebase-test-project")
            self.assertEqual(json.loads(settings.fcm_service_account_json)["type"], "service_account")

    def test_malformed_service_account_configuration_fails_closed(self):
        encoded = base64.b64encode(b'{"type":"authorized_user"}').decode()
        with patch.dict("os.environ", {"FCM_SERVICE_ACCOUNT_B64": encoded}, clear=True):
            with self.assertRaisesRegex(RuntimeError, "FCM"):
                Settings.from_env()


class FcmAccountIsolationTests(unittest.TestCase):
    def test_session_sends_only_to_its_android_devices_and_owns_unregistration(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "ninebot"
            settings = Settings(
                "gateway-token",
                ninebot_config_dir=root,
                accounts_path=root / "accounts.json",
                snapshot_db_path=root / "snapshots.db",
            )
            adapter = NinePlusAdapter(settings)
            self.addCleanup(adapter.snapshot_store.close)
            for account in ("alice", "bob"):
                salt, digest = AccountStore._password_hash(f"{account}-password")
                adapter.account_store._accounts[account] = {
                    "account_id": AccountStore._account_id(account),
                    "password_salt": salt,
                    "password_hash": digest,
                    "config_dir": str(root / "accounts" / AccountStore._account_id(account)),
                    "ninebot_username": account,
                }
            adapter.account_store._save()
            alice_session = adapter.login("alice", "alice-password")["session_token"]
            bob_session = adapter.login("bob", "bob-password")["session_token"]

            adapter.register_push_device(alice_session, "alice-android", platform="android")
            adapter.register_push_device(alice_session, "alice-ios", platform="ios")
            adapter.register_push_device(bob_session, "bob-android", platform="android")

            class FakePusher:
                def __init__(self):
                    self.tokens = []

                def send(self, token, title, body, data):
                    self.tokens.append(token)
                    return {"sent": True, "mode": "v1", "status": 200}

            sender = FakePusher()
            adapter.fcm = sender
            result = adapter.send_push_to_session(alice_session, "title", "body")

            self.assertEqual(result["sent"], 1)
            self.assertEqual(result["total"], 1)
            self.assertEqual(sender.tokens, ["alice-android"])
            self.assertFalse(adapter.unregister_push_device(bob_session, "alice-android")["removed"])
            self.assertTrue(adapter.unregister_push_device(alice_session, "alice-android")["removed"])


if __name__ == "__main__":
    unittest.main()
