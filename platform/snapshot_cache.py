"""SQLite snapshot cache, request coalescing, and vehicle command audit.

Read path buffering for NinePlus Platform (NPP-22):
- durable snapshots with per-type TTL
- single-flight upstream fetch per (account, vehicle, data_type)
- stale-while-revalidate for expired rows
- command_id / idempotency audit for vehicle controls (no auto-retry)
"""

from __future__ import annotations

import json
import sqlite3
import threading
import time
import uuid
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

SCHEMA_VERSION = 1

# Default TTLs (seconds) — NPP-22 缓冲契约
TTL_VEHICLES = 600
TTL_DASHBOARD = 90
TTL_STATUS = 90
TTL_BATTERY = 90
TTL_TRAVEL_CURRENT = 600
TTL_TRAVEL_HISTORICAL = 86_400
TTL_TRAVEL_DETAIL = 86_400


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def iso(value: datetime | None = None) -> str:
    return (value or utc_now()).isoformat()


@dataclass(frozen=True)
class SnapshotHit:
    payload: Any
    source_updated_at: str | None
    fetched_at: str
    stale: bool
    error: str | None = None


class CacheMetrics:
    """Small process-local counters for rollout comparison and health checks."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._counts: Counter[str] = Counter()

    def increment(self, name: str) -> None:
        with self._lock:
            self._counts[name] += 1

    def snapshot(self) -> dict[str, int]:
        with self._lock:
            return dict(self._counts)


class SnapshotStore:
    """Durable vehicle read snapshots. Cache failures must not block startup."""

    def __init__(self, db_path: Path):
        self.db_path = Path(db_path)
        self._lock = threading.RLock()
        self._conn: sqlite3.Connection | None = None
        try:
            self.db_path.parent.mkdir(parents=True, exist_ok=True)
            self._conn = sqlite3.connect(self.db_path, check_same_thread=False)
            self._conn.row_factory = sqlite3.Row
            self._conn.execute("PRAGMA journal_mode=WAL")
            self._conn.execute("PRAGMA synchronous=NORMAL")
            self._migrate()
        except (sqlite3.Error, OSError) as exc:
            print(f"snapshot cache disabled (sqlite): {exc}", file=__import__("sys").stderr)
            self._conn = None

    def _migrate(self) -> None:
        assert self._conn is not None
        self._conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS vehicle_snapshots (
                account_id TEXT NOT NULL,
                vehicle_sn TEXT NOT NULL,
                data_type TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                source_updated_at TEXT,
                fetched_at TEXT NOT NULL,
                expires_at REAL NOT NULL,
                error TEXT,
                schema_version INTEGER NOT NULL,
                PRIMARY KEY (account_id, vehicle_sn, data_type)
            );
            CREATE TABLE IF NOT EXISTS vehicle_commands (
                command_id TEXT PRIMARY KEY,
                account_id TEXT NOT NULL,
                vehicle_sn TEXT NOT NULL,
                action TEXT NOT NULL,
                idempotency_key TEXT,
                requested_at TEXT NOT NULL,
                sent_at TEXT,
                result_json TEXT,
                status TEXT NOT NULL,
                error TEXT,
                expires_at REAL
            );
            CREATE UNIQUE INDEX IF NOT EXISTS idx_commands_idem
                ON vehicle_commands(account_id, idempotency_key)
                WHERE idempotency_key IS NOT NULL;
            """
        )
        self._conn.commit()

    @property
    def enabled(self) -> bool:
        return self._conn is not None

    def get(self, account_id: str, vehicle_sn: str, data_type: str) -> SnapshotHit | None:
        if not self._conn:
            return None
        with self._lock:
            row = self._conn.execute(
                "SELECT * FROM vehicle_snapshots WHERE account_id=? AND vehicle_sn=? AND data_type=?",
                (account_id, vehicle_sn, data_type),
            ).fetchone()
        if row is None:
            return None
        try:
            payload = json.loads(row["payload_json"])
        except ValueError:
            return None
        stale = float(row["expires_at"]) < time.time()
        return SnapshotHit(
            payload=payload,
            source_updated_at=row["source_updated_at"],
            fetched_at=row["fetched_at"],
            stale=stale,
            error=row["error"],
        )

    def put(
        self,
        account_id: str,
        vehicle_sn: str,
        data_type: str,
        payload: Any,
        ttl_seconds: int,
        source_updated_at: str | None = None,
        error: str | None = None,
    ) -> SnapshotHit:
        fetched = iso()
        expires = time.time() + max(0, int(ttl_seconds))
        encoded = json.dumps(payload, ensure_ascii=False)
        if self._conn:
            try:
                with self._lock:
                    self._conn.execute(
                        """
                        INSERT INTO vehicle_snapshots
                            (account_id, vehicle_sn, data_type, payload_json,
                             source_updated_at, fetched_at, expires_at, error, schema_version)
                        VALUES (?,?,?,?,?,?,?,?,?)
                        ON CONFLICT(account_id, vehicle_sn, data_type) DO UPDATE SET
                            payload_json=excluded.payload_json,
                            source_updated_at=excluded.source_updated_at,
                            fetched_at=excluded.fetched_at,
                            expires_at=excluded.expires_at,
                            error=excluded.error,
                            schema_version=excluded.schema_version
                        """,
                        (
                            account_id, vehicle_sn, data_type, encoded,
                            source_updated_at, fetched, expires, error, SCHEMA_VERSION,
                        ),
                    )
                    self._conn.commit()
            except sqlite3.Error as exc:
                print(f"snapshot write failed: {exc}", file=__import__("sys").stderr)
        return SnapshotHit(
            payload=payload,
            source_updated_at=source_updated_at,
            fetched_at=fetched,
            stale=False,
            error=error,
        )

    def close(self) -> None:
        if self._conn:
            with self._lock:
                self._conn.close()
            self._conn = None


class RequestCoalescer:
    """One upstream fetch per key; concurrent callers wait for the same result."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._flights: dict[str, "_Flight"] = {}

    def run(self, key: str, fn: Callable[[], Any]) -> Any:
        with self._lock:
            flight = self._flights.get(key)
            if flight is None:
                flight = _Flight()
                self._flights[key] = flight
                leader = True
            else:
                leader = False
        if leader:
            try:
                flight.payload = fn()
            except BaseException as exc:  # noqa: BLE001 - re-raise to waiters
                flight.error = exc
            finally:
                flight.event.set()
                with self._lock:
                    self._flights.pop(key, None)
        if not flight.event.wait(timeout=40):
            raise TimeoutError(f"coalesced fetch timed out: {key}")
        if flight.error is not None:
            raise flight.error
        return flight.payload


class _Flight:
    __slots__ = ("event", "payload", "error")

    def __init__(self) -> None:
        self.event = threading.Event()
        self.payload: Any = None
        self.error: BaseException | None = None


class CommandAudit:
    """Vehicle control audit trail. Dangerous actions are never auto-retried."""

    DANGEROUS = frozenset({"engine_start", "engine_stop", "buck"})

    def __init__(self, store: SnapshotStore):
        self.store = store

    def _conn(self) -> sqlite3.Connection | None:
        return getattr(self.store, "_conn", None)

    def begin(
        self,
        account_id: str,
        vehicle_sn: str,
        action: str,
        idempotency_key: str | None = None,
    ) -> dict[str, Any]:
        command_id = str(uuid.uuid4())
        requested = iso()
        conn = self._conn()
        if conn is None:
            raise RuntimeError("车控审计不可用，命令未发送")
        try:
            # Claim and look up a key in one transaction. Losing callers replay
            # the persisted winner rather than sending another vehicle command.
            with self.store._lock, conn:  # noqa: SLF001
                cursor = conn.execute(
                        """
                        INSERT INTO vehicle_commands
                            (command_id, account_id, vehicle_sn, action, idempotency_key,
                             requested_at, status)
                        VALUES (?,?,?,?,?,?,?)
                        ON CONFLICT DO NOTHING
                        """,
                        (command_id, account_id, vehicle_sn, action, idempotency_key, requested, "accepted"),
                )
                if cursor.rowcount == 0:
                    row = conn.execute(
                        "SELECT * FROM vehicle_commands WHERE account_id=? AND idempotency_key=?",
                        (account_id, idempotency_key),
                    ).fetchone()
                    if row is None:
                        raise RuntimeError("车控审计冲突，命令未发送")
                    if row["vehicle_sn"] != vehicle_sn or row["action"] != action:
                        raise ValueError("幂等键已用于另一车辆或操作")
                    return {
                        "command_id": row["command_id"],
                        "status": row["status"],
                        "result": json.loads(row["result_json"]) if row["result_json"] else None,
                        "error": row["error"],
                        "replayed": True,
                    }
        except sqlite3.Error as exc:
            raise RuntimeError("车控审计写入失败，命令未发送") from exc
        return {
            "command_id": command_id,
            "status": "accepted",
            "result": None,
            "error": None,
            "replayed": False,
        }

    def complete(self, command_id: str, result: Any) -> None:
        self._finish(command_id, "succeeded", result, None)

    def fail(self, command_id: str, error: str, status: str = "failed") -> None:
        self._finish(command_id, status, None, error)

    def unknown(self, command_id: str, error: str) -> None:
        self._finish(command_id, "unknown", None, error)

    def _finish(self, command_id: str, status: str, result: Any, error: str | None) -> None:
        conn = self._conn()
        if conn is None:
            return
        try:
            with self.store._lock:  # noqa: SLF001
                conn.execute(
                    """
                    UPDATE vehicle_commands
                    SET status=?, sent_at=?, result_json=?, error=?
                    WHERE command_id=?
                    """,
                    (
                        status,
                        iso(),
                        json.dumps(result, ensure_ascii=False) if result is not None else None,
                        error,
                        command_id,
                    ),
                )
                conn.commit()
        except sqlite3.Error as exc:
            print(f"command audit update failed: {exc}", file=__import__("sys").stderr)
