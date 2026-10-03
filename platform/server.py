#!/usr/bin/env python3
"""NinePlus-compatible HTTP adapter backed by the Ninebot cloud."""

from __future__ import annotations

import json
import base64
import hashlib
import hmac
import html
import os
import secrets
import subprocess
import sys
import tempfile
import threading
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Callable

from snapshot_cache import (
    TTL_BATTERY,
    TTL_DASHBOARD,
    TTL_STATUS,
    TTL_TRAVEL_CURRENT,
    TTL_TRAVEL_DETAIL,
    TTL_TRAVEL_HISTORICAL,
    TTL_VEHICLES,
    CacheMetrics,
    CommandAudit,
    RequestCoalescer,
    SnapshotStore,
    iso,
)


def _env(name: str, default: str = "") -> str:
    return os.environ.get(name, default).strip()


def _env_bool(name: str, default: bool) -> bool:
    raw = _env(name)
    if not raw:
        return default
    return raw.lower() not in {"0", "false", "no", "off"}


def _secret_env(name: str, legacy_name: str) -> str:
    encoded = _env(name)
    if encoded:
        try:
            return base64.b64decode(encoded, validate=True).decode("utf-8")
        except (ValueError, UnicodeDecodeError) as exc:
            raise RuntimeError(f"{name} 不是有效的 Base64 配置") from exc
    return _env(legacy_name)


@dataclass(frozen=True)
class Settings:
    bearer_token: str
    account: str = ""
    password: str = ""
    ninebot_username: str = ""
    ninebot_password: str = ""
    ninebot_config_dir: Path = Path("/data/ninebot")
    accounts_path: Path = Path("/data/ninebot/accounts.json")
    admin_password: str = ""
    buffer_enabled: bool = True
    snapshot_db_path: Path = Path("/data/ninebot/snapshots.db")
    fcm_service_account_json: str = ""
    fcm_project_id: str = ""

    @classmethod
    def from_env(cls) -> "Settings":
        service_account_json = ""
        service_account_path = _env("FCM_SERVICE_ACCOUNT_PATH")
        if service_account_path:
            try:
                service_account_json = Path(service_account_path).read_text(encoding="utf-8")
            except OSError as exc:
                raise RuntimeError("FCM service account file is unavailable") from exc
        elif _env("FCM_SERVICE_ACCOUNT_B64"):
            try:
                service_account_json = base64.b64decode(
                    _env("FCM_SERVICE_ACCOUNT_B64"), validate=True,
                ).decode("utf-8")
            except (ValueError, UnicodeDecodeError) as exc:
                raise RuntimeError("FCM_SERVICE_ACCOUNT_B64 is invalid") from exc

        project_id = _env("FCM_PROJECT_ID")
        if service_account_json:
            try:
                service_account = json.loads(service_account_json)
            except json.JSONDecodeError as exc:
                raise RuntimeError("FCM service account JSON is invalid") from exc
            if not isinstance(service_account, dict) or service_account.get("type") != "service_account":
                raise RuntimeError("FCM service account JSON must be a service account key")
            if not service_account.get("client_email") or not service_account.get("private_key"):
                raise RuntimeError("FCM service account JSON is missing required fields")
            project_id = project_id or str(service_account.get("project_id", ""))
            if not project_id:
                raise RuntimeError("FCM_PROJECT_ID is required")
        return cls(
            bearer_token=_env("NINEPLUS_BEARER_TOKEN"),
            account=_env("NINEPLUS_ACCOUNT"),
            password=_secret_env("NINEPLUS_PASSWORD_B64", "NINEPLUS_PASSWORD"),
            ninebot_username=_env("NINEBOT_USERNAME"),
            ninebot_password=_secret_env("NINEBOT_PASSWORD_B64", "NINEBOT_PASSWORD"),
            ninebot_config_dir=Path(_env("NINEBOT_CONFIG_DIR", "/data/ninebot")),
            accounts_path=Path(_env("NINEPLUS_ACCOUNTS", "/data/ninebot/accounts.json")),
            admin_password=_secret_env("NINEPLUS_ADMIN_PASSWORD_B64", "NINEPLUS_ADMIN_PASSWORD") or _secret_env("NINEPLUS_PASSWORD_B64", "NINEPLUS_PASSWORD"),
            buffer_enabled=_env_bool("NINEPLUS_BUFFER_ENABLED", True),
            snapshot_db_path=Path(_env("NINEPLUS_SNAPSHOT_DB", "/data/ninebot/snapshots.db")),
            fcm_service_account_json=service_account_json,
            fcm_project_id=project_id,
        )


class AccountStore:
    """Persistent NineBot+ users with isolated ninecli token directories."""

    def __init__(self, settings: Settings):
        self.settings = settings
        self.path = settings.accounts_path
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        self._meta: dict[str, Any] = {}
        self._accounts = self._load()
        self._migrate_legacy_account()

    def _load(self) -> dict[str, dict[str, Any]]:
        if not self.path.exists():
            return {}
        with self.path.open("r", encoding="utf-8") as handle:
            payload = json.load(handle)
        if isinstance(payload, dict) and isinstance(payload.get("meta"), dict):
            self._meta = dict(payload["meta"])
        values = payload.get("accounts", payload) if isinstance(payload, dict) else {}
        if not isinstance(values, dict):
            raise RuntimeError("accounts.json 格式无效")
        return {str(key): value for key, value in values.items() if isinstance(value, dict)}

    def _save(self) -> None:
        temporary = self.path.with_suffix(".tmp")
        with temporary.open("w", encoding="utf-8") as handle:
            json.dump({"version": 1, "meta": self._meta, "accounts": self._accounts}, handle, ensure_ascii=False, indent=2)
        os.chmod(temporary, 0o600)
        temporary.replace(self.path)

    @staticmethod
    def _password_hash(password: str, salt_hex: str | None = None) -> tuple[str, str]:
        salt = bytes.fromhex(salt_hex) if salt_hex else secrets.token_bytes(16)
        digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, 210_000)
        return salt.hex(), digest.hex()

    @staticmethod
    def _account_id(account: str) -> str:
        return hashlib.sha256(account.casefold().encode("utf-8")).hexdigest()[:24]

    @staticmethod
    def _is_mobile_phone(value: str) -> bool:
        return len(value) == 11 and value.isascii() and value.isdigit() and value[0] == "1" and value[1] in "3456789"

    def _migrate_legacy_account(self) -> None:
        if self._accounts or not self.settings.account or not self.settings.password:
            return
        salt, digest = self._password_hash(self.settings.password)
        self._accounts[self.settings.account] = {
            "account_id": self._account_id(self.settings.account),
            "password_salt": salt,
            "password_hash": digest,
            "ninebot_username": self.settings.ninebot_username,
            "config_dir": str(self.settings.ninebot_config_dir),
            "created_at": datetime.now(timezone.utc).isoformat(),
            "migrated": True,
        }
        self._save()

    def list_accounts(self) -> list[dict[str, Any]]:
        with self._lock:
            return [
                {
                    "account": account,
                    "ninebot_username": record.get("ninebot_username", ""),
                    "created_at": record.get("created_at", ""),
                }
                for account, record in sorted(self._accounts.items())
            ]

    def admin_configured(self) -> bool:
        return bool(self.settings.admin_password or self._meta.get("admin_password_hash"))

    def authenticate_admin(self, password: str) -> bool:
        salt = str(self._meta.get("admin_password_salt", ""))
        digest = str(self._meta.get("admin_password_hash", ""))
        if salt and digest:
            _, candidate = self._password_hash(password, salt)
            return hmac.compare_digest(candidate, digest)
        return bool(self.settings.admin_password) and hmac.compare_digest(password, self.settings.admin_password)

    def setup_admin(self, password: str) -> None:
        if len(password) < 8:
            raise ValueError("管理员密码至少需要 8 位")
        with self._lock:
            if self.admin_configured():
                raise ValueError("管理员密码已经设置")
            salt, digest = self._password_hash(password)
            self._meta["admin_password_salt"] = salt
            self._meta["admin_password_hash"] = digest
            self._save()

    def change_admin_password(self, current_password: str, new_password: str) -> None:
        if not self.authenticate_admin(current_password):
            raise ValueError("当前管理员密码错误")
        if len(new_password) < 8:
            raise ValueError("新管理员密码至少需要 8 位")
        with self._lock:
            salt, digest = self._password_hash(new_password)
            self._meta["admin_password_salt"] = salt
            self._meta["admin_password_hash"] = digest
            self._save()

    def authenticate(self, account: str, password: str) -> dict[str, Any] | None:
        with self._lock:
            record = self._accounts.get(account)
            if not record:
                return None
            _, digest = self._password_hash(password, str(record.get("password_salt", "")))
            return dict(record) if hmac.compare_digest(digest, str(record.get("password_hash", ""))) else None

    def add_account(self, app_account: str, app_password: str, ninebot_username: str, ninebot_password: str) -> dict[str, Any]:
        app_account = app_account.strip()
        ninebot_username = ninebot_username.strip()
        if not app_account or not app_password or not ninebot_username or not ninebot_password:
            raise ValueError("NineBot+ 账号、NineBot+ 密码、九号账号和九号密码均不能为空")
        if not self._is_mobile_phone(app_account):
            raise ValueError("NineBot+ 账号必须是有效的手机号")
        if len(app_password) < 8:
            raise ValueError("NineBot+ 密码至少需要 8 位")
        if not self._is_mobile_phone(ninebot_username):
            raise ValueError("九号出行账号必须是有效的手机号")
        with self._lock:
            if app_account in self._accounts:
                raise ValueError("NineBot+ 账号已存在")
            account_id = self._account_id(app_account)
            config_dir = self.settings.ninebot_config_dir / "accounts" / account_id
            account_settings = self._settings_for(config_dir, ninebot_username, ninebot_password)
            client = DirectNinebotClient(account_settings)
            vehicles = client.vehicles()  # Validate the Ninebot login before persisting the user.
            salt, digest = self._password_hash(app_password)
            record = {
                "account_id": account_id,
                "password_salt": salt,
                "password_hash": digest,
                "ninebot_username": ninebot_username,
                "config_dir": str(config_dir),
                "created_at": datetime.now(timezone.utc).isoformat(),
            }
            self._accounts[app_account] = record
            self._save()
            return {"account": app_account, "vehicle_count": len(vehicles), **record}

    def client_settings(self, record: dict[str, Any]) -> Settings:
        return self._settings_for(Path(str(record["config_dir"])), str(record.get("ninebot_username", "")), "")

    def _settings_for(self, config_dir: Path, username: str, password: str) -> Settings:
        return Settings(
            self.settings.bearer_token, self.settings.account, self.settings.password,
            ninebot_username=username, ninebot_password=password,
            ninebot_config_dir=config_dir, accounts_path=self.settings.accounts_path,
            admin_password=self.settings.admin_password,
        )


class DirectNinebotClient:
    """Small synchronous wrapper around the same ninecli used by hasscc/ninebot."""

    def __init__(self, settings: Settings):
        self.settings = settings
        self.settings.ninebot_config_dir.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        if not (self.settings.ninebot_config_dir / "tokens.json").exists():
            if not settings.ninebot_username or not settings.ninebot_password:
                raise RuntimeError("首次启动需要 NINEBOT_USERNAME 和 NINEBOT_PASSWORD")
            self.run("login", "-u", settings.ninebot_username, "-p", settings.ninebot_password)

    def run(self, *args: str) -> Any:
        command = [
            sys.executable, "-m", "ninecli", "--config",
            str(self.settings.ninebot_config_dir), *args, "--json",
        ]
        with self._lock:
            result = subprocess.run(command, capture_output=True, text=True, timeout=35, check=False)
        if result.returncode != 0:
            detail = (result.stderr or result.stdout or "ninecli 调用失败").strip()
            raise RuntimeError(detail[:500])
        try:
            return json.loads(result.stdout or "{}")
        except ValueError as exc:
            raise RuntimeError("ninecli 返回了无效 JSON") from exc

    def vehicles(self) -> list[dict[str, Any]]:
        payload = self.run("vehicles")
        values = payload if isinstance(payload, list) else payload.get("data", [])
        return values if isinstance(values, list) else []

    def ensure_vehicle(self, sn: str) -> None:
        if not any(str(vehicle.get("wnumber") or vehicle.get("sn")) == sn for vehicle in self.vehicles()):
            raise KeyError(sn)

    def dashboard(self, sn: str) -> dict[str, Any]:
        month = datetime.now().strftime("%Y%m")
        status = self.run("status", sn)
        battery = self.run("battery", sn)
        try:
            travel = self.run("travel", sn, "--month", month)
        except RuntimeError:
            travel = {"month": month, "list": []}
        return {
            "vehicle": next((v for v in self.vehicles() if str(v.get("wnumber") or v.get("sn")) == sn), {"sn": sn, "wnumber": sn}),
            "status": status,
            "battery": battery,
            "travel": travel,
            "updated_at": datetime.now(timezone.utc).isoformat(),
        }

    def status_only(self, sn: str) -> dict[str, Any]:
        payload = self.run("status", sn)
        return payload if isinstance(payload, dict) else {}

    def battery_only(self, sn: str) -> dict[str, Any]:
        payload = self.run("battery", sn)
        return payload if isinstance(payload, dict) else {}

    def travel(self, sn: str, month: str) -> dict[str, Any]:
        payload = self.run("travel", sn, "--month", month)
        return payload if isinstance(payload, dict) else {"month": month, "list": []}

    def travel_detail(self, sn: str, travel_id: str) -> dict[str, Any]:
        payload = self.run("travel", sn, "--detail", travel_id)
        if not isinstance(payload, dict):
            raise RuntimeError(f"行程 {travel_id} 的详情格式无效")
        return payload

    def action(self, sn: str, action: str) -> Any:
        commands = {
            "bell": ("bell", sn),
            "buck": ("buck", sn, "--yes"),
            "engine_start": ("engine-start", sn, "--yes"),
            "engine_stop": ("engine-stop", sn, "--yes"),
        }
        command = commands.get(action)
        if command is None:
            raise NotImplementedError(action)
        return self.run(*command)


class PushDeviceStore:
    """Persist per-account APNs/FCM registrations in the protected data volume."""

    def __init__(self, settings: Settings):
        self.path = settings.ninebot_config_dir / "push_devices.json"
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        self._devices: dict[str, dict[str, Any]] = self._load()

    def _load(self) -> dict[str, dict[str, Any]]:
        if not self.path.exists():
            return {}
        with self.path.open("r", encoding="utf-8") as handle:
            payload = json.load(handle)
        values = payload.get("devices", payload) if isinstance(payload, dict) else {}
        if not isinstance(values, dict):
            raise RuntimeError("push_devices.json format is invalid")
        return {str(key): value for key, value in values.items() if isinstance(value, dict)}

    def _save(self) -> None:
        temporary_path: Path | None = None
        try:
            with tempfile.NamedTemporaryFile(
                mode="w",
                encoding="utf-8",
                dir=self.path.parent,
                prefix=f".{self.path.name}.",
                suffix=".tmp",
                delete=False,
            ) as handle:
                temporary_path = Path(handle.name)
                json.dump({"version": 1, "devices": self._devices}, handle, ensure_ascii=False, indent=2)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temporary_path, self.path)
        finally:
            if temporary_path is not None:
                temporary_path.unlink(missing_ok=True)

    @staticmethod
    def key(token: str) -> str:
        return hashlib.sha256(token.encode("utf-8")).hexdigest()[:32]

    @staticmethod
    def infer_platform(token: str, bundle_id: str = "") -> str:
        bundle = bundle_id.casefold()
        if "android" in bundle or bundle.endswith(".android"):
            return "android"
        if "ios" in bundle or bundle.endswith(".ios"):
            return "ios"
        # Legacy clients omit platform; FCM tokens contain a colon or exceed
        # the fixed-size hexadecimal APNs token format.
        if ":" in token or len(token) > 120:
            return "android"
        return "ios"

    def register(
        self,
        token: str,
        account: str,
        bundle_id: str = "",
        environment: str = "",
        platform: str = "",
    ) -> dict[str, Any]:
        token = token.strip()
        if not token:
            raise ValueError("缺少推送 Token")
        resolved_platform = platform.strip().lower() or self.infer_platform(token, bundle_id)
        if resolved_platform not in {"android", "ios"}:
            raise ValueError("platform must be android or ios")
        device_id = self.key(token)
        with self._lock:
            self._devices[device_id] = {
                "token": token,
                "account": account,
                "bundle_id": bundle_id,
                "environment": environment or "production",
                "platform": resolved_platform,
                "updated_at": datetime.now(timezone.utc).isoformat(),
            }
            self._save()
        return {"device_id": device_id, "platform": resolved_platform}

    def devices_for(self, account: str, platform: str | None = None) -> list[dict[str, Any]]:
        with self._lock:
            return [
                dict(device)
                for device in self._devices.values()
                if device.get("account") == account and
                (platform is None or device.get("platform") == platform)
            ]

    def all_devices(self) -> list[dict[str, Any]]:
        with self._lock:
            return [dict(device) for device in self._devices.values()]

    def remove(self, token: str, account: str) -> bool:
        device_id = self.key(token.strip())
        with self._lock:
            device = self._devices.get(device_id)
            if device is None or device.get("account") != account:
                return False
            del self._devices[device_id]
            self._save()
            return True


class _UrllibAuthResponse:
    def __init__(self, status: int, headers: dict[str, str], data: bytes):
        self.status = status
        self.headers = headers
        self.data = data


class _UrllibAuthRequest:
    """google-auth transport using stdlib urllib and the configured proxy."""

    def __call__(
        self,
        url: str,
        method: str = "GET",
        body: bytes | None = None,
        headers: dict[str, str] | None = None,
        timeout: float | None = None,
        **_kwargs: Any,
    ) -> _UrllibAuthResponse:
        request = urllib.request.Request(url, data=body, method=method, headers=headers or {})
        try:
            with urllib.request.urlopen(request, timeout=timeout or 30) as response:
                return _UrllibAuthResponse(response.status, dict(response.headers), response.read())
        except urllib.error.HTTPError as exc:
            return _UrllibAuthResponse(exc.code, dict(exc.headers or {}), exc.read())


class FcmPusher:
    """FCM HTTP v1 sender using a locally configured Firebase service account."""

    SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
    ENDPOINT = "https://fcm.googleapis.com/v1/projects/{project_id}/messages:send"

    def __init__(
        self,
        settings: Settings,
        credentials_factory: Callable[..., Any] | None = None,
        auth_request_factory: Callable[[], Any] | None = None,
        urlopen: Callable[..., Any] | None = None,
    ):
        self.settings = settings
        self._credentials_factory = credentials_factory
        self._auth_request_factory = auth_request_factory or _UrllibAuthRequest
        self._urlopen = urlopen
        self._credentials: Any = None
        self._credentials_lock = threading.Lock()

    def configured(self) -> bool:
        return bool(self.settings.fcm_service_account_json and self.settings.fcm_project_id)

    def mode(self) -> str:
        return "v1" if self.configured() else "none"

    def _load_credentials(self) -> Any:
        factory = self._credentials_factory
        if factory is None:
            from google.oauth2 import service_account
            factory = service_account.Credentials.from_service_account_info
        return factory(
            json.loads(self.settings.fcm_service_account_json),
            scopes=[self.SCOPE],
        )

    def _access_token(self) -> str:
        with self._credentials_lock:
            if self._credentials is None:
                self._credentials = self._load_credentials()
            if not self._credentials.valid:
                self._credentials.refresh(self._auth_request_factory()())
            token = self._credentials.token
            if not token:
                raise RuntimeError("FCM OAuth token was not issued")
            return str(token)

    @staticmethod
    def _provider_error(status: int, payload: bytes) -> str:
        try:
            parsed = json.loads(payload)
            error = parsed.get("error", {}) if isinstance(parsed, dict) else {}
            code = error.get("status") or error.get("code")
            if code:
                return str(code)[:80]
        except (ValueError, AttributeError):
            pass
        return f"HTTP_{status}"

    def send(
        self,
        token: str,
        title: str,
        body: str,
        data: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        if not self.configured():
            return {"sent": False, "reason": "FCM is not configured"}
        if not token.strip():
            return {"sent": False, "reason": "empty registration token"}
        message: dict[str, Any] = {
            "token": token,
            "notification": {"title": title, "body": body},
            "data": {str(key): str(value) for key, value in (data or {}).items()},
            "android": {
                "priority": "high",
                "notification": {"channel_id": "push", "sound": "default"},
            },
        }
        try:
            request = urllib.request.Request(
                self.ENDPOINT.format(project_id=urllib.parse.quote(self.settings.fcm_project_id, safe="")),
                data=json.dumps({"message": message}).encode("utf-8"),
                headers={
                    "Authorization": f"Bearer {self._access_token()}",
                    "Content-Type": "application/json; charset=utf-8",
                },
                method="POST",
            )
            opener = self._urlopen or urllib.request.urlopen
            with opener(request, timeout=15) as response:
                result = json.loads(response.read() or b"{}")
                message_name = result.get("name") if isinstance(result, dict) else None
                if response.status != 200 or not message_name:
                    return {"sent": False, "status": response.status, "reason": "invalid FCM success response", "mode": "v1"}
                return {"sent": True, "status": response.status, "message_id": str(message_name), "mode": "v1"}
        except urllib.error.HTTPError as exc:
            response_body = exc.read()
            exc.close()
            return {
                "sent": False,
                "status": exc.code,
                "reason": self._provider_error(exc.code, response_body),
                "mode": "v1",
            }
        except Exception as exc:
            # Exception text can contain request or credential details. Report
            # only the class; diagnostic secrets stay out of API responses.
            return {"sent": False, "reason": type(exc).__name__, "mode": "v1"}


class BufferedNinebotClient:
    """Read-path cache + single-flight around DirectNinebotClient.

    Buffer failures fall through to the direct path. `NINEPLUS_BUFFER_ENABLED=0`
    restores pure direct behavior.
    """

    def __init__(
        self,
        inner: DirectNinebotClient,
        account_id: str,
        store: SnapshotStore,
        coalescer: RequestCoalescer,
        commands: CommandAudit,
        enabled: bool = True,
        metrics: CacheMetrics | None = None,
    ):
        self.inner = inner
        self.account_id = account_id
        self.store = store
        self.coalescer = coalescer
        self.commands = commands
        self.enabled = enabled and store.enabled
        self.metrics = metrics or CacheMetrics()
        self._request_state = threading.local()
        self._refresh_lock = threading.Lock()
        self._refreshing: set[str] = set()

    @property
    def settings(self) -> Settings:
        return self.inner.settings

    @property
    def last_meta(self) -> dict[str, Any]:
        return getattr(self._request_state, "meta", {})

    @last_meta.setter
    def last_meta(self, meta: dict[str, Any]) -> None:
        self._request_state.meta = meta

    def _meta(self, hit: Any) -> dict[str, Any]:
        meta = {
            "fetched_at": getattr(hit, "fetched_at", iso()) if hit else iso(),
            "source_updated_at": getattr(hit, "source_updated_at", None),
            "stale": bool(getattr(hit, "stale", False)),
            "buffered": bool(hit),
        }
        self.last_meta = meta
        return meta

    def _read(
        self,
        vehicle_sn: str,
        data_type: str,
        ttl: int,
        fetch: Callable[[], Any],
    ) -> tuple[Any, dict[str, Any]]:
        if not self.enabled:
            self.metrics.increment("direct_reads")
            self.metrics.increment("upstream_fetches")
            payload = fetch()
            self._meta(None)
            return payload, self.last_meta
        hit = self.store.get(self.account_id, vehicle_sn, data_type)
        if hit is not None and not hit.stale:
            self.metrics.increment("cache_hits")
            self._meta(hit)
            return hit.payload, self.last_meta

        key = f"{self.account_id}|{vehicle_sn}|{data_type}"
        self.metrics.increment("cache_misses" if hit is None else "expired_reads")

        try:
            fresh = self.coalescer.run(
                key, lambda: self._load_snapshot(vehicle_sn, data_type, ttl, fetch),
            )
        except Exception:
            if hit is not None:
                # Keep last known good; mark stale so clients can show freshness.
                self._meta(hit)
                self.last_meta = dict(self.last_meta, stale=True, buffered=True)
                return hit.payload, self.last_meta
            raise
        return fresh.payload, self._meta(fresh)

    def _load_snapshot(
        self, vehicle_sn: str, data_type: str, ttl: int, fetch: Callable[[], Any],
    ) -> Any:
        # Check again after winning a flight: another caller may have published
        # the snapshot between the initial read and scheduling this operation.
        current = self.store.get(self.account_id, vehicle_sn, data_type)
        if current is not None and not current.stale:
            return current
        self.metrics.increment("upstream_fetches")
        payload = fetch()
        return self.store.put(
            self.account_id, vehicle_sn, data_type, payload, ttl,
            source_updated_at=_source_updated_at(payload),
        )

    def _read_stale_ok(
        self,
        vehicle_sn: str,
        data_type: str,
        ttl: int,
        fetch: Callable[[], Any],
    ) -> tuple[Any, dict[str, Any]]:
        """Fresh → return; stale → return immediately and refresh in background; miss → bounded sync."""
        if not self.enabled:
            return self._read(vehicle_sn, data_type, ttl, fetch)
        hit = self.store.get(self.account_id, vehicle_sn, data_type)
        if hit is not None and not hit.stale:
            self.metrics.increment("cache_hits")
            self._meta(hit)
            return hit.payload, self.last_meta
        if hit is not None and hit.stale:
            self.metrics.increment("stale_served")
            self._meta(hit)
            self._refresh_stale(vehicle_sn, data_type, ttl, fetch)
            return hit.payload, self.last_meta
        return self._read(vehicle_sn, data_type, ttl, fetch)

    def _refresh_stale(
        self,
        vehicle_sn: str,
        data_type: str,
        ttl: int,
        fetch: Callable[[], Any],
    ) -> None:
        key = f"{self.account_id}|{vehicle_sn}|{data_type}"
        with self._refresh_lock:
            if key in self._refreshing:
                self.metrics.increment("swr_deduplicated")
                return
            self._refreshing.add(key)
            self.metrics.increment("swr_scheduled")

        def refresh() -> None:
            try:
                self.coalescer.run(
                    key, lambda: self._load_snapshot(vehicle_sn, data_type, ttl, fetch),
                )
                self.metrics.increment("refresh_successes")
            except Exception as exc:  # noqa: BLE001 - background refresh must not crash
                self.metrics.increment("refresh_failures")
                print(f"background refresh failed {data_type}/{vehicle_sn}: {exc}", file=sys.stderr)
            finally:
                with self._refresh_lock:
                    self._refreshing.discard(key)

        try:
            threading.Thread(target=refresh, name=f"swr-{data_type}", daemon=True).start()
        except RuntimeError:
            with self._refresh_lock:
                self._refreshing.discard(key)
            self.metrics.increment("refresh_failures")
            raise

    def vehicles(self) -> list[dict[str, Any]]:
        payload, _ = self._read_stale_ok("*", "vehicles", TTL_VEHICLES, self.inner.vehicles)
        return payload if isinstance(payload, list) else []

    def ensure_vehicle(self, sn: str) -> None:
        if not any(str(v.get("wnumber") or v.get("sn")) == sn for v in self.vehicles()):
            raise KeyError(sn)

    def dashboard(self, sn: str) -> tuple[dict[str, Any], dict[str, Any]]:
        month = datetime.now().strftime("%Y%m")
        status, status_meta = self._read_stale_ok(sn, "status", TTL_STATUS, lambda: self.inner.status_only(sn))
        battery, battery_meta = self._read_stale_ok(sn, "battery", TTL_BATTERY, lambda: self.inner.battery_only(sn))
        travel, travel_meta = self._read_stale_ok(
            sn, f"travel:{month}", TTL_TRAVEL_CURRENT,
            lambda: self.inner.travel(sn, month),
        )
        vehicles, vehicles_meta = self._read_stale_ok("*", "vehicles", TTL_VEHICLES, self.inner.vehicles)
        vehicle = next(
            (v for v in vehicles if str(v.get("wnumber") or v.get("sn")) == sn),
            {"sn": sn, "wnumber": sn},
        )
        payload = {
            "vehicle": vehicle,
            "status": status,
            "battery": battery,
            "travel": travel,
            "updated_at": datetime.now(timezone.utc).isoformat(),
        }
        components = {
            "status": status_meta,
            "battery": battery_meta,
            "travel": travel_meta,
            "vehicles": vehicles_meta,
        }
        return payload, {
            "fetched_at": status_meta.get("fetched_at", iso()),
            "source_updated_at": status_meta.get("source_updated_at"),
            "stale": any(bool(meta.get("stale")) for meta in components.values()),
            "buffered": all(bool(meta.get("buffered")) for meta in components.values()),
            "components": components,
        }

    def status_only(self, sn: str) -> dict[str, Any]:
        payload, _ = self._read_stale_ok(sn, "status", TTL_STATUS, lambda: self.inner.status_only(sn))
        return payload

    def battery_only(self, sn: str) -> dict[str, Any]:
        payload, _ = self._read_stale_ok(sn, "battery", TTL_BATTERY, lambda: self.inner.battery_only(sn))
        return payload

    def travel(self, sn: str, month: str) -> dict[str, Any]:
        current = datetime.now().strftime("%Y%m")
        ttl = TTL_TRAVEL_CURRENT if month == current else TTL_TRAVEL_HISTORICAL
        payload, _ = self._read_stale_ok(sn, f"travel:{month}", ttl, lambda: self.inner.travel(sn, month))
        return payload

    def travel_detail(self, sn: str, travel_id: str) -> dict[str, Any]:
        payload, _ = self._read(
            sn, f"travel_detail:{travel_id}", TTL_TRAVEL_DETAIL,
            lambda: self.inner.travel_detail(sn, travel_id),
        )
        return payload

    def action(self, account_key: str, sn: str, action: str, idempotency_key: str | None = None) -> Any:
        record = self.commands.begin(account_key, sn, action, idempotency_key)
        if record.get("replayed"):
            return {
                "command_id": record["command_id"],
                "status": record["status"],
                "result": record.get("result"),
                "replayed": True,
            }
        command_id = record["command_id"]
        try:
            result = self.inner.action(sn, action)
        except subprocess.TimeoutExpired as exc:
            self.commands.unknown(command_id, f"timeout: {exc}")
            raise RuntimeError("车控超时，结果未知，请查看车辆状态后再操作（不会自动重试）") from exc
        except Exception as exc:  # noqa: BLE001
            self.commands.fail(command_id, str(exc))
            raise
        self.commands.complete(command_id, result)
        return {"command_id": command_id, "status": "succeeded", "result": result, "replayed": False}


def _source_updated_at(payload: Any) -> str | None:
    if isinstance(payload, dict):
        for key in ("updated_at", "updatedAt", "source_updated_at", "sourceUpdatedAt"):
            value = payload.get(key)
            if isinstance(value, str) and value:
                return value
    return None


class NinePlusAdapter:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.account_store = AccountStore(settings)
        self.push_devices = PushDeviceStore(settings)
        self.fcm = FcmPusher(settings)
        self._sessions: dict[str, tuple[str, dict[str, Any]]] = {}
        self._clients: dict[str, BufferedNinebotClient] = {}
        self._session_lock = threading.RLock()
        self._admin_sessions: set[str] = set()
        self.snapshot_store = SnapshotStore(settings.snapshot_db_path)
        self.coalescer = RequestCoalescer()
        self.command_audit = CommandAudit(self.snapshot_store)
        self.cache_metrics = CacheMetrics()

    def login(self, account: str, password: str) -> dict[str, Any] | None:
        record = self.account_store.authenticate(account, password) or {}
        if not record:
            return None
        token = secrets.token_urlsafe(32)
        with self._session_lock:
            self._sessions[token] = (account, record)
        return {"phone": account, "session_token": token}

    def client_for_session(self, token: str) -> BufferedNinebotClient | None:
        with self._session_lock:
            session = self._sessions.get(token)
            if not session:
                return None
            account, record = session
            client = self._clients.get(account)
            if client is None:
                inner = DirectNinebotClient(self.account_store.client_settings(record))
                client = BufferedNinebotClient(
                    inner,
                    account_id=AccountStore._account_id(account),
                    store=self.snapshot_store,
                    coalescer=self.coalescer,
                    commands=self.command_audit,
                    enabled=self.settings.buffer_enabled,
                    metrics=self.cache_metrics,
                )
                self._clients[account] = client
            return client

    def session_account(self, token: str) -> str | None:
        with self._session_lock:
            session = self._sessions.get(token)
            return session[0] if session else None

    def register_push_device(
        self,
        session_token: str,
        token: str,
        bundle_id: str = "",
        environment: str = "",
        platform: str = "",
    ) -> dict[str, Any]:
        account = self.session_account(session_token)
        if not account:
            raise RuntimeError("登录会话无效，请重新登录")
        registration = self.push_devices.register(token, account, bundle_id, environment, platform)
        return {"accepted": True, "fcm_configured": self.fcm.configured(), **registration}

    def unregister_push_device(self, session_token: str, token: str) -> dict[str, Any]:
        account = self.session_account(session_token)
        if not account:
            raise RuntimeError("登录会话无效，请重新登录")
        return {"removed": self.push_devices.remove(token, account)}

    def send_push_to_session(
        self,
        session_token: str,
        title: str,
        body: str,
        data: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        account = self.session_account(session_token)
        if not account:
            raise RuntimeError("登录会话无效，请重新登录")
        devices = self.push_devices.devices_for(account, platform="android")
        results = []
        for device in devices:
            result = self.fcm.send(device["token"], title, body, data)
            results.append({"device_id": self.push_devices.key(device["token"]), **result})
        return {"sent": sum(bool(result.get("sent")) for result in results), "total": len(results), "results": results}

    def new_admin_session(self) -> str:
        token = secrets.token_urlsafe(32)
        with self._session_lock:
            self._admin_sessions.add(token)
        return token

    def admin_configured(self) -> bool:
        return self.account_store.admin_configured()

    def authenticate_admin(self, password: str) -> bool:
        return self.account_store.authenticate_admin(password)

    def setup_admin(self, password: str) -> None:
        self.account_store.setup_admin(password)

    def change_admin_password(self, current_password: str, new_password: str) -> None:
        self.account_store.change_admin_password(current_password, new_password)
        with self._session_lock:
            self._admin_sessions.clear()

    def is_admin_session(self, token: str) -> bool:
        with self._session_lock:
            return token in self._admin_sessions

class Handler(BaseHTTPRequestHandler):
    adapter: NinePlusAdapter
    server_version = "NinePlusHA/1.0"

    def log_message(self, fmt: str, *args: Any) -> None:
        print(f"{self.address_string()} - {fmt % args}", file=sys.stderr)

    def _body(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0"))
        if length == 0:
            return {}
        raw = self.rfile.read(length)
        if "application/x-www-form-urlencoded" in self.headers.get("Content-Type", ""):
            values = urllib.parse.parse_qs(raw.decode("utf-8"), keep_blank_values=True)
            return {key: items[-1] for key, items in values.items()}
        return json.loads(raw)

    def _reply(self, status: int, data: Any = None, error: str | None = None, meta: dict[str, Any] | None = None) -> None:
        payload: dict[str, Any] = {"ok": error is None}
        payload["data" if error is None else "error"] = data if error is None else {"message": error}
        if error is None and meta:
            payload["meta"] = meta
            self._last_meta = meta
        encoded = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        if error is None and meta:
            if meta.get("stale"):
                self.send_header("X-NinePlus-Stale", "1")
            if meta.get("fetched_at"):
                self.send_header("X-NinePlus-Fetched-At", str(meta["fetched_at"]))
        self.end_headers()
        self.wfile.write(encoded)

    def _html_reply(self, status: int, document: str, cookie: str | None = None) -> None:
        encoded = document.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.send_header("Cache-Control", "no-store")
        if cookie:
            self.send_header("Set-Cookie", cookie)
        self.end_headers()
        self.wfile.write(encoded)

    def _admin_token(self) -> str:
        cookies = self.headers.get("Cookie", "")
        for item in cookies.split(";"):
            key, separator, value = item.strip().partition("=")
            if separator and key == "nineplus_admin":
                return value
        return ""

    def _admin_page(self, message: str = "", error: str = "") -> str:
        content = """<section><h2>新增账号</h2><form method="post" action="/admin/accounts" onsubmit="this.querySelector('button').disabled=true;this.querySelector('button').textContent='正在验证九号账号，请稍候…';"><label>NineBot+ 登录账号（手机号）</label><input name="app_account" type="tel" inputmode="numeric" autocomplete="username" pattern="1[3-9][0-9]{9}" maxlength="11" title="请输入 11 位手机号" required><label>NineBot+ 登录密码（至少 8 位）</label><input name="app_password" type="password" autocomplete="new-password" minlength="8" required><label>九号出行账号（手机号）</label><input name="ninebot_username" type="tel" inputmode="numeric" autocomplete="username" pattern="1[3-9][0-9]{9}" maxlength="11" title="请输入 11 位手机号" required><label>九号出行密码</label><input name="ninebot_password" type="password" autocomplete="off" required><p class="hint">九号密码仅用于本次登录换取令牌，不写入 accounts.json。</p><button type="submit">验证九号账号并新增</button></form></section>"""
        return self._admin_layout("新增账号", content, message, error)

    def _admin_accounts_page(self, message: str = "", error: str = "") -> str:
        rows = ""
        for account in self.adapter.account_store.list_accounts():
            rows += (
                "<tr><td>" + html.escape(str(account["account"])) + "</td><td>" +
                html.escape(str(account["ninebot_username"])) + "</td><td>" +
                html.escape(str(account["created_at"])) + "</td></tr>"
            )
        content = f"""<section><h2>已有账号</h2><table><thead><tr><th>NineBot+ 账号</th><th>九号账号</th><th>创建时间</th></tr></thead><tbody>{rows or '<tr><td colspan="3">暂无账号</td></tr>'}</tbody></table></section>"""
        return self._admin_layout("已有账号", content, message, error)

    def _admin_password_page(self, message: str = "", error: str = "") -> str:
        content = """<section><h2>修改管理员密码</h2><form method="post" action="/admin/password" onsubmit="this.querySelector('button').disabled=true;this.querySelector('button').textContent='正在修改，请稍候…';"><label>当前密码</label><input name="current_password" type="password" autocomplete="current-password" required><label>新密码（至少 8 位）</label><input name="new_password" type="password" autocomplete="new-password" minlength="8" required><label>再次输入新密码</label><input name="new_password_confirm" type="password" autocomplete="new-password" minlength="8" required><button type="submit">修改管理员密码</button></form></section>"""
        return self._admin_layout("修改管理员密码", content, message, error)

    @staticmethod
    def _admin_layout(title: str, content: str, message: str = "", error: str = "") -> str:
        notice = f'<p class="ok">{html.escape(message)}</p>' if message else ""
        notice += f'<p class="error">{html.escape(error)}</p>' if error else ""
        return f"""<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>NinePlus 账号管理</title><style>
body{{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;margin:0;background:#f4f6f8;color:#17202a}}main{{max-width:820px;margin:40px auto;padding:0 18px}}nav{{display:flex;gap:10px;flex-wrap:wrap;margin:0 0 20px}}nav a{{padding:10px 14px;border-radius:9px;background:white;color:#17202a;text-decoration:none;box-shadow:0 3px 12px #0000000a}}nav a.active{{background:#14181c;color:white}}section{{background:white;border-radius:16px;padding:22px;margin-bottom:18px;box-shadow:0 8px 28px #0000000d}}h1,h2{{margin-top:0}}label{{display:block;font-size:14px;margin:13px 0 5px}}input{{box-sizing:border-box;width:100%;padding:11px;border:1px solid #ccd2d8;border-radius:9px;font-size:16px}}button{{margin-top:18px;padding:11px 18px;border:0;border-radius:9px;background:#14181c;color:white;font-size:15px}}table{{width:100%;border-collapse:collapse}}th,td{{padding:10px 8px;text-align:left;border-bottom:1px solid #e8ebee;font-size:14px}}.ok{{color:#16803c}}.error{{color:#c9342f}}.hint{{color:#66717c;font-size:13px}}</style></head><body><main><h1>NinePlus 账号管理</h1><nav><a class="{'active' if title == '新增账号' else ''}" href="/admin">新增账号</a><a class="{'active' if title == '已有账号' else ''}" href="/admin/accounts">已有账号</a><a class="{'active' if title == '修改管理员密码' else ''}" href="/admin/password">修改管理员密码</a></nav>{notice}{content}</main></body></html>"""

    def _admin_login_page(self, error: str = "", message: str = "") -> str:
        notice = f'<p style="color:#16803c">{html.escape(message)}</p>' if message else ""
        notice += f'<p style="color:#c9342f">{html.escape(error)}</p>' if error else ""
        return f"""<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>NinePlus 后台登录</title></head><body style="font-family:-apple-system,sans-serif;background:#f4f6f8"><main style="max-width:420px;margin:80px auto;background:white;padding:28px;border-radius:16px"><h1>NinePlus 后台</h1>{notice}<form method="post" action="/admin/login"><label>管理员密码</label><input name="password" type="password" required style="box-sizing:border-box;width:100%;padding:12px;margin:8px 0;border:1px solid #ccd2d8;border-radius:9px"><button style="padding:11px 18px;border:0;border-radius:9px;background:#14181c;color:white">登录</button></form></main></body></html>"""

    def _admin_setup_page(self, error: str = "") -> str:
        notice = f'<p style="color:#c9342f">{html.escape(error)}</p>' if error else ""
        return f"""<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>初始化 NinePlus 后台</title></head><body style="font-family:-apple-system,sans-serif;background:#f4f6f8"><main style="max-width:420px;margin:80px auto;background:white;padding:28px;border-radius:16px"><h1>初始化后台</h1><p>第一次使用，请设置管理员密码。密码至少 8 位。</p>{notice}<form method="post" action="/admin/setup"><label>设置管理员密码</label><input name="password" type="password" required minlength="8" style="box-sizing:border-box;width:100%;padding:12px;margin:8px 0;border:1px solid #ccd2d8;border-radius:9px"><label>再次输入</label><input name="password_confirm" type="password" required minlength="8" style="box-sizing:border-box;width:100%;padding:12px;margin:8px 0;border:1px solid #ccd2d8;border-radius:9px"><button style="padding:11px 18px;border:0;border-radius:9px;background:#14181c;color:white">保存并进入后台</button></form></main></body></html>"""

    def _authorized(self) -> bool:
        required = self.adapter.settings.bearer_token
        return not required or self.headers.get("Authorization") == f"Bearer {required}"

    def _dispatch(self) -> None:
        parsed = urllib.parse.urlparse(self.path)
        parts = [part for part in parsed.path.split("/") if part]
        method = self.command
        body = self._body() if method == "POST" else {}

        if parts == ["admin", "setup"] and method == "POST":
            if self.adapter.admin_configured():
                self._html_reply(HTTPStatus.SEE_OTHER, '<meta http-equiv="refresh" content="0;url=/admin">')
                return
            if body.get("password") != body.get("password_confirm"):
                self._html_reply(HTTPStatus.BAD_REQUEST, self._admin_setup_page("两次输入的密码不一致"))
                return
            try:
                self.adapter.setup_admin(str(body.get("password", "")))
                token = self.adapter.new_admin_session()
                self._html_reply(HTTPStatus.SEE_OTHER, '<meta http-equiv="refresh" content="0;url=/admin">', f"nineplus_admin={token}; Path=/admin; HttpOnly; SameSite=Strict")
            except ValueError as exc:
                self._html_reply(HTTPStatus.BAD_REQUEST, self._admin_setup_page(str(exc)))
            return
        if parts == ["admin", "login"] and method == "POST":
            if not self.adapter.admin_configured():
                self._html_reply(HTTPStatus.OK, self._admin_setup_page())
                return
            if not self.adapter.authenticate_admin(str(body.get("password", ""))):
                self._html_reply(HTTPStatus.UNAUTHORIZED, self._admin_login_page("管理员密码错误"))
                return
            token = self.adapter.new_admin_session()
            self._html_reply(HTTPStatus.SEE_OTHER, '<meta http-equiv="refresh" content="0;url=/admin">', f"nineplus_admin={token}; Path=/admin; HttpOnly; SameSite=Strict")
            return
        if parts in (["admin"], ["admin", "accounts"], ["admin", "password"]) and method == "GET":
            if not self.adapter.is_admin_session(self._admin_token()):
                page = self._admin_login_page() if self.adapter.admin_configured() else self._admin_setup_page()
                self._html_reply(HTTPStatus.OK, page)
            else:
                query = urllib.parse.parse_qs(parsed.query)
                message = query.get("message", [""])[0]
                error = query.get("error", [""])[0]
                page = self._admin_page(message, error)
                if parts == ["admin", "accounts"]:
                    page = self._admin_accounts_page(message, error)
                elif parts == ["admin", "password"]:
                    page = self._admin_password_page(message, error)
                self._html_reply(HTTPStatus.OK, page)
            return
        if parts == ["admin", "password"] and method == "POST":
            if not self.adapter.is_admin_session(self._admin_token()):
                self._html_reply(HTTPStatus.UNAUTHORIZED, self._admin_login_page("登录已失效"))
                return
            if body.get("new_password") != body.get("new_password_confirm"):
                self._html_reply(HTTPStatus.BAD_REQUEST, self._admin_password_page(error="两次输入的新密码不一致"))
                return
            try:
                self.adapter.change_admin_password(
                    str(body.get("current_password", "")), str(body.get("new_password", "")),
                )
                self._html_reply(
                    HTTPStatus.OK,
                    self._admin_login_page(message="密码修改成功，请使用新密码登录"),
                    "nineplus_admin=; Path=/admin; HttpOnly; SameSite=Strict; Max-Age=0",
                )
            except ValueError as exc:
                self._html_reply(HTTPStatus.BAD_REQUEST, self._admin_password_page(error=str(exc)))
            return
        if parts == ["admin", "accounts"] and method == "POST":
            if not self.adapter.is_admin_session(self._admin_token()):
                self._html_reply(HTTPStatus.UNAUTHORIZED, self._admin_login_page("登录已失效"))
                return
            if not self.adapter.account_store:
                self._html_reply(HTTPStatus.BAD_REQUEST, self._admin_page(error="仅直连模式支持多账号"))
                return
            try:
                result = self.adapter.account_store.add_account(
                    str(body.get("app_account", "")), str(body.get("app_password", "")),
                    str(body.get("ninebot_username", "")), str(body.get("ninebot_password", "")),
                )
                message = f"九号账号验证成功，NineBot+ 账号新增成功，发现 {result['vehicle_count']} 辆车"
                self._html_reply(HTTPStatus.OK, self._admin_accounts_page(message=message))
            except (RuntimeError, ValueError) as exc:
                self._html_reply(HTTPStatus.BAD_REQUEST, self._admin_page(error=str(exc)))
            return

        if parts == ["healthz"] and method == "GET":
            backend = "ninebot-cloud-multi-account"
            account_count = len(self.adapter.account_store.list_accounts())
            self._reply(HTTPStatus.OK, {
                "status": "ok",
                "backend": backend,
                "accounts": account_count,
                "buffer_enabled": self.adapter.settings.buffer_enabled and self.adapter.snapshot_store.enabled,
                "cache_metrics": self.adapter.cache_metrics.snapshot(),
                "push_devices": len(self.adapter.push_devices.all_devices()),
                "fcm_configured": self.adapter.fcm.configured(),
                "fcm_mode": self.adapter.fcm.mode(),
            })
            return
        if not self._authorized():
            self._reply(HTTPStatus.UNAUTHORIZED, error="Bearer Token 无效")
            return
        if parts == ["accounts", "login"] and method == "POST":
            result = self.adapter.login(str(body.get("account", "")), str(body.get("password", "")))
            if not result:
                self._reply(HTTPStatus.UNAUTHORIZED, error="账号或密码错误")
                return
            self._reply(HTTPStatus.OK, result)
            return
        direct_client = self.adapter.client_for_session(self.headers.get("X-NinePlus-Session", ""))
        if direct_client is None:
            self._reply(HTTPStatus.UNAUTHORIZED, error="登录会话无效，请重新登录")
            return
        if parts == ["vehicles"] and method == "GET":
            vehicles = direct_client.vehicles()
            self._reply(HTTPStatus.OK, {"vehicles": vehicles}, meta=direct_client.last_meta)
            return
        if len(parts) >= 3 and parts[0] == "vehicles":
            sn, endpoint = parts[1], parts[2]
            if direct_client:
                direct_client.ensure_vehicle(sn)
            if method == "GET" and endpoint == "dashboard":
                value, meta = direct_client.dashboard(sn)
                self._reply(HTTPStatus.OK, value, meta=meta)
                return
            if method == "GET" and endpoint in {"status", "battery"}:
                value = direct_client.status_only(sn) if endpoint == "status" else direct_client.battery_only(sn)
                self._reply(HTTPStatus.OK, value, meta=direct_client.last_meta)
                return
            if method == "GET" and endpoint == "travel" and len(parts) == 4:
                travel_id = urllib.parse.unquote(parts[3])
                self._reply(HTTPStatus.OK, direct_client.travel_detail(sn, travel_id), meta=direct_client.last_meta)
                return
            if method == "GET" and endpoint == "travel":
                month = urllib.parse.parse_qs(parsed.query).get("month", [datetime.now().strftime("%Y%m")])[0]
                value = direct_client.travel(sn, month)
                self._reply(HTTPStatus.OK, value, meta=direct_client.last_meta)
                return
            if method == "POST" and endpoint == "travel-sync":
                month = urllib.parse.parse_qs(parsed.query).get("month", [datetime.now().strftime("%Y%m")])[0]
                self._reply(HTTPStatus.OK, {"month": month, "records": [], "total": 0})
                return
            if method == "GET" and endpoint == "prediction":
                self._reply(HTTPStatus.OK, {})
                return
            if method == "POST" and endpoint == "prediction-settings":
                self._reply(HTTPStatus.OK, {"battery_chemistry": body})
                return
            action = None
            if method == "POST" and endpoint in {"bell", "buck"}:
                action = endpoint
            elif method == "POST" and len(parts) == 4 and endpoint == "engine" and parts[3] in {"start", "stop"}:
                action = f"engine_{parts[3]}"
            if action:
                account_key = self.adapter.session_account(self.headers.get("X-NinePlus-Session", "")) or "anonymous"
                idem = body.get("idempotency_key") or self.headers.get("Idempotency-Key")
                result = direct_client.action(account_key, sn, action, str(idem) if idem else None)
                self._reply(HTTPStatus.OK, result)
                return
        if method == "POST" and parts == ["devices", "register"]:
            try:
                registration = self.adapter.register_push_device(
                    session_token=self.headers.get("X-NinePlus-Session", ""),
                    token=str(body.get("token", "")),
                    bundle_id=str(body.get("bundle_id", "")),
                    environment=str(body.get("environment", "")),
                    platform=str(body.get("platform", "")),
                )
            except ValueError as exc:
                self._reply(HTTPStatus.BAD_REQUEST, error=str(exc))
                return
            except RuntimeError as exc:
                self._reply(HTTPStatus.UNAUTHORIZED, error=str(exc))
                return
            self._reply(HTTPStatus.OK, registration)
            return
        if method == "POST" and parts == ["devices", "unregister"]:
            try:
                result = self.adapter.unregister_push_device(
                    self.headers.get("X-NinePlus-Session", ""),
                    str(body.get("token", "")),
                )
            except RuntimeError as exc:
                self._reply(HTTPStatus.UNAUTHORIZED, error=str(exc))
                return
            self._reply(HTTPStatus.OK, result)
            return
        if method == "POST" and parts == ["push", "test"]:
            try:
                data = body.get("data") if isinstance(body.get("data"), dict) else {}
                result = self.adapter.send_push_to_session(
                    self.headers.get("X-NinePlus-Session", ""),
                    str(body.get("title") or "NinePlus 测试推送"),
                    str(body.get("body") or "远程推送链路测试"),
                    data,
                )
            except RuntimeError as exc:
                self._reply(HTTPStatus.UNAUTHORIZED, error=str(exc))
                return
            self._reply(HTTPStatus.OK, result)
            return
        if method == "POST" and parts == ["live-activities", "register"]:
            self._reply(HTTPStatus.OK, {"accepted": False, "reason": "APNs is not configured"})
            return
        self._reply(HTTPStatus.NOT_FOUND, error="接口不存在")

    def do_GET(self) -> None:  # noqa: N802
        self._handle()

    def do_POST(self) -> None:  # noqa: N802
        self._handle()

    def _handle(self) -> None:
        try:
            self._dispatch()
        except KeyError:
            self._reply(HTTPStatus.NOT_FOUND, error="车辆不存在")
        except NotImplementedError as exc:
            self._reply(HTTPStatus.NOT_IMPLEMENTED, error=str(exc))
        except (RuntimeError, ValueError, json.JSONDecodeError) as exc:
            self._reply(HTTPStatus.BAD_GATEWAY, error=str(exc))
        except Exception as exc:  # Keep an API response while logging unexpected failures.
            print(f"unexpected error: {exc!r}", file=sys.stderr)
            self._reply(HTTPStatus.INTERNAL_SERVER_ERROR, error="服务端内部错误")


def main() -> None:
    settings = Settings.from_env()
    Handler.adapter = NinePlusAdapter(settings)
    host = _env("HOST", "0.0.0.0")
    port = int(_env("PORT", "19009"))
    print(f"NinePlus server listening on http://{host}:{port}")
    ThreadingHTTPServer((host, port), Handler).serve_forever()


if __name__ == "__main__":
    main()
