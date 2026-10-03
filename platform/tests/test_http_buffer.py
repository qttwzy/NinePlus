import json
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from datetime import datetime
from http.server import ThreadingHTTPServer
from pathlib import Path
from unittest.mock import MagicMock

from server import BufferedNinebotClient, DirectNinebotClient, Handler, NinePlusAdapter, Settings
from snapshot_cache import CommandAudit, RequestCoalescer, SnapshotStore


class BufferedHttpTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.store = SnapshotStore(Path(self.temp.name) / "snapshots.db")
        self.addCleanup(self.store.close)
        self.inner = MagicMock(spec=DirectNinebotClient)
        self.refresh_release = threading.Event()
        self.client = BufferedNinebotClient(
            self.inner, "test-account", self.store, RequestCoalescer(), CommandAudit(self.store),
        )
        self.adapter = MagicMock(spec=NinePlusAdapter)
        self.adapter.settings = Settings("", snapshot_db_path=self.store.db_path)
        self.adapter.snapshot_store = self.store
        self.adapter.cache_metrics = self.client.metrics
        self.adapter.account_store = MagicMock()
        self.adapter.account_store.list_accounts.return_value = []
        self.adapter.push_devices = MagicMock()
        self.adapter.push_devices.all_devices.return_value = []
        self.adapter.fcm = MagicMock()
        self.adapter.fcm.configured.return_value = False
        self.adapter.fcm.mode.return_value = "none"
        self.adapter.client_for_session.return_value = self.client
        self.adapter.session_account.return_value = "test-account"
        self.adapter.register_push_device.return_value = {
            "accepted": True,
            "fcm_configured": False,
            "device_id": "hashed-device-id",
            "platform": "android",
        }
        self.adapter.unregister_push_device.return_value = {"removed": True}
        self.adapter.send_push_to_session.return_value = {"sent": 0, "total": 0, "results": []}

        class TestHandler(Handler):
            def log_message(self, *_args):
                pass

        TestHandler.adapter = self.adapter
        self.http = ThreadingHTTPServer(("127.0.0.1", 0), TestHandler)
        self.thread = threading.Thread(target=self.http.serve_forever)
        self.thread.start()
        self.addCleanup(self.stop_server)
        self.base = f"http://127.0.0.1:{self.http.server_port}"

    def stop_server(self):
        self.refresh_release.set()
        self.http.shutdown()
        self.http.server_close()
        self.thread.join(2)

    def request(self, path):
        with urllib.request.urlopen(self.base + path, timeout=2) as response:
            return json.load(response), response.headers

    def test_dashboard_stale_header_matches_component_metadata(self):
        month = datetime.now().strftime("%Y%m")
        self.store.put("test-account", "*", "vehicles", [{"wnumber": "SN1"}], 60)
        self.store.put("test-account", "SN1", "status", {"pwr": 0}, 60)
        self.store.put("test-account", "SN1", "battery", {"electricity": 80}, 0)
        self.store.put("test-account", "SN1", f"travel:{month}", {"list": []}, 60)
        self.inner.battery_only.side_effect = lambda _sn: (
            self.refresh_release.wait(2), {"electricity": 79},
        )[1]
        response, headers = self.request("/vehicles/SN1/dashboard")
        self.assertTrue(response["ok"])
        self.assertEqual(response["data"]["battery"]["electricity"], 80)
        self.assertTrue(response["meta"]["stale"])
        self.assertTrue(response["meta"]["components"]["battery"]["stale"])
        self.assertEqual(headers["X-NinePlus-Stale"], "1")
        self.assertEqual(headers["X-NinePlus-Fetched-At"], response["meta"]["fetched_at"])

    def test_health_reports_cache_counts_without_account_data(self):
        self.client.metrics.increment("cache_hits")
        response, _ = self.request("/healthz")
        self.assertTrue(response["data"]["buffer_enabled"])
        self.assertEqual(response["data"]["cache_metrics"]["cache_hits"], 1)
        self.assertNotIn("test-account", json.dumps(response))

    def test_concurrent_command_replays_do_not_send_again(self):
        calls = []
        results = []
        errors = []
        barrier = threading.Barrier(8)

        def fake_action(_sn, _action):
            calls.append(1)
            time.sleep(0.05)
            return {"ok": True}

        self.inner.action.side_effect = fake_action

        def command():
            try:
                barrier.wait(2)
                results.append(self.client.action("test-account", "SN1", "engine_start", "one-operation"))
            except Exception as error:
                errors.append(error)

        threads = [threading.Thread(target=command) for _ in range(8)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(2)
            self.assertFalse(thread.is_alive())
        self.assertEqual(errors, [])
        self.assertEqual(len(results), 8)
        self.assertEqual(len(calls), 1)
        self.assertEqual(len({result["command_id"] for result in results}), 1)
        replay = self.client.action("test-account", "SN1", "engine_start", "one-operation")
        self.assertTrue(replay["replayed"])
        self.assertEqual(replay["status"], "succeeded")
        self.assertEqual(len(calls), 1)

    def test_command_requires_durable_audit_before_sending(self):
        self.store.close()
        with self.assertRaisesRegex(RuntimeError, "审计不可用"):
            self.client.action("test-account", "SN1", "engine_start", "one-operation")
        self.inner.action.assert_not_called()

    def test_push_device_routes_require_session_and_keep_tokens_out_of_response(self):
        body = json.dumps({
            "token": "registration-token-secret",
            "bundle_id": "com.example.ninebotplus",
            "environment": "production",
            "platform": "android",
        }).encode()
        request = urllib.request.Request(
            self.base + "/devices/register",
            data=body,
            headers={
                "Authorization": "Bearer gateway-token",
                "X-NinePlus-Session": "session-a",
                "Content-Type": "application/json",
            },
            method="POST",
        )
        with urllib.request.urlopen(request, timeout=2) as response:
            payload = json.load(response)
        self.assertTrue(payload["data"]["accepted"])
        self.assertNotIn("registration-token-secret", json.dumps(payload))
        self.adapter.register_push_device.assert_called_once_with(
            session_token="session-a",
            token="registration-token-secret",
            bundle_id="com.example.ninebotplus",
            environment="production",
            platform="android",
        )

        unauthorized = urllib.request.Request(
            self.base + "/devices/register",
            data=body,
            headers={"Authorization": "Bearer gateway-token", "Content-Type": "application/json"},
            method="POST",
        )
        self.adapter.client_for_session.return_value = None
        with self.assertRaises(urllib.error.HTTPError) as error:
            urllib.request.urlopen(unauthorized, timeout=2)
        self.assertEqual(error.exception.code, 401)
        error.exception.close()

    def test_push_test_route_uses_authenticated_session_without_exposing_device_tokens(self):
        self.adapter.send_push_to_session.return_value = {
            "sent": 1,
            "total": 1,
            "results": [{"device_id": "hashed-device-id", "sent": True, "status": 200}],
        }
        request = urllib.request.Request(
            self.base + "/push/test",
            data=json.dumps({"title": "Test", "body": "Hello", "data": {"source": "test"}}).encode(),
            headers={
                "Authorization": "Bearer gateway-token",
                "X-NinePlus-Session": "session-a",
                "Content-Type": "application/json",
            },
            method="POST",
        )
        with urllib.request.urlopen(request, timeout=2) as response:
            payload = json.load(response)
        self.assertEqual(payload["data"]["sent"], 1)
        self.assertNotIn("registration-token-secret", json.dumps(payload))
        self.adapter.send_push_to_session.assert_called_once_with(
            "session-a", "Test", "Hello", {"source": "test"},
        )

    def test_push_test_rejects_a_missing_session(self):
        self.adapter.client_for_session.return_value = None
        request = urllib.request.Request(
            self.base + "/push/test",
            data=b"{}",
            headers={"Authorization": "Bearer gateway-token", "Content-Type": "application/json"},
            method="POST",
        )
        with self.assertRaises(urllib.error.HTTPError) as error:
            urllib.request.urlopen(request, timeout=2)
        self.assertEqual(error.exception.code, 401)
        error.exception.close()
        self.adapter.send_push_to_session.assert_not_called()
