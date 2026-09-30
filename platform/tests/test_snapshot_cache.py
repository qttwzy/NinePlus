import json
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import MagicMock

from snapshot_cache import (
    TTL_STATUS,
    CommandAudit,
    RequestCoalescer,
    SnapshotStore,
    iso,
)


class SnapshotStoreTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = SnapshotStore(Path(self.temp.name) / "snapshots.db")

    def tearDown(self):
        self.store.close()
        self.temp.cleanup()

    def test_put_get_roundtrip_and_stale_flag(self):
        self.store.put("acct", "SN1", "status", {"dump_energy": 80}, ttl_seconds=60)
        hit = self.store.get("acct", "SN1", "status")
        self.assertIsNotNone(hit)
        self.assertEqual(hit.payload["dump_energy"], 80)
        self.assertFalse(hit.stale)

        self.store.put("acct", "SN1", "status", {"dump_energy": 79}, ttl_seconds=0)
        time.sleep(0.01)
        stale = self.store.get("acct", "SN1", "status")
        self.assertTrue(stale.stale)
        self.assertEqual(stale.payload["dump_energy"], 79)

    def test_survives_reopen(self):
        self.store.put("acct", "SN1", "battery", {"electricity": 50}, ttl_seconds=3600)
        self.store.close()
        reopened = SnapshotStore(Path(self.temp.name) / "snapshots.db")
        hit = reopened.get("acct", "SN1", "battery")
        self.assertIsNotNone(hit)
        self.assertEqual(hit.payload["electricity"], 50)
        reopened.close()

    def test_corrupt_db_does_not_raise(self):
        path = Path(self.temp.name) / "broken.db"
        path.write_bytes(b"not a database")
        store = SnapshotStore(path)
        self.assertFalse(store.enabled)
        self.assertIsNone(store.get("a", "b", "c"))
        store.close()


class CoalescerTests(unittest.TestCase):
    def test_concurrent_calls_single_upstream(self):
        coalescer = RequestCoalescer()
        calls = []
        started = threading.Barrier(4)

        def fetch():
            calls.append(1)
            time.sleep(0.05)
            return {"ok": True}

        results = []

        def worker():
            started.wait(timeout=2)
            results.append(coalescer.run("k", fetch))

        threads = [threading.Thread(target=worker) for _ in range(4)]
        for t in threads:
            t.start()
        for t in threads:
            t.join(timeout=2)
        self.assertEqual(len(calls), 1)
        self.assertEqual(len(results), 4)
        self.assertTrue(all(r == {"ok": True} for r in results))


class CommandAuditTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = SnapshotStore(Path(self.temp.name) / "snapshots.db")
        self.audit = CommandAudit(self.store)

    def tearDown(self):
        self.store.close()
        self.temp.cleanup()

    def test_begin_complete_records_command(self):
        record = self.audit.begin("acct", "SN1", "bell", "idem-1")
        self.assertFalse(record["replayed"])
        self.audit.complete(record["command_id"], {"ok": True})
        again = self.audit.begin("acct", "SN1", "bell", "idem-1")
        self.assertTrue(again["replayed"])
        self.assertEqual(again["command_id"], record["command_id"])
        self.assertEqual(again["status"], "succeeded")

    def test_unknown_on_timeout_path(self):
        record = self.audit.begin("acct", "SN1", "engine_start", None)
        self.audit.unknown(record["command_id"], "timeout")
        # no auto-retry: new begin with same idempotency is a different command
        other = self.audit.begin("acct", "SN1", "engine_start", "idem-2")
        self.assertNotEqual(other["command_id"], record["command_id"])

    def test_dangerous_set(self):
        self.assertIn("engine_start", CommandAudit.DANGEROUS)
        self.assertIn("engine_stop", CommandAudit.DANGEROUS)


class BufferedClientTests(unittest.TestCase):
    def test_swr_returns_stale_and_refreshes(self):
        from server import BufferedNinebotClient, DirectNinebotClient, Settings

        temp = tempfile.TemporaryDirectory()
        root = Path(temp.name)
        (root / "tokens.json").write_text("{}", encoding="utf-8")
        settings = Settings("", ninebot_config_dir=root)
        store = SnapshotStore(root / "snapshots.db")
        inner = MagicMock(spec=DirectNinebotClient)
        inner.status_only.return_value = {"dump_energy": 10, "updated_at": iso()}
        client = BufferedNinebotClient(
            inner, "acct", store, RequestCoalescer(), CommandAudit(store), enabled=True,
        )

        # miss → upstream
        payload, meta = client._read_stale_ok("SN1", "status", TTL_STATUS, lambda: inner.status_only("SN1"))
        self.assertEqual(payload["dump_energy"], 10)
        self.assertFalse(meta["stale"])
        self.assertEqual(inner.status_only.call_count, 1)

        # fresh hit → no upstream
        payload, meta = client._read_stale_ok("SN1", "status", TTL_STATUS, lambda: inner.status_only("SN1"))
        self.assertEqual(inner.status_only.call_count, 1)

        # force stale
        store.put("acct", "SN1", "status", {"dump_energy": 10}, ttl_seconds=0)
        inner.status_only.return_value = {"dump_energy": 9, "updated_at": iso()}
        payload, meta = client._read_stale_ok("SN1", "status", TTL_STATUS, lambda: inner.status_only("SN1"))
        self.assertEqual(payload["dump_energy"], 10)  # stale served
        self.assertTrue(meta["stale"])
        deadline = time.time() + 1.5
        while time.time() < deadline and inner.status_only.call_count < 2:
            time.sleep(0.02)
        self.assertGreaterEqual(inner.status_only.call_count, 2)
        store.close()
        temp.cleanup()

    def test_buffer_off_goes_direct(self):
        from server import BufferedNinebotClient, DirectNinebotClient, Settings

        temp = tempfile.TemporaryDirectory()
        root = Path(temp.name)
        (root / "tokens.json").write_text("{}", encoding="utf-8")
        settings = Settings("", ninebot_config_dir=root)
        store = SnapshotStore(root / "snapshots.db")
        inner = MagicMock(spec=DirectNinebotClient)
        inner.vehicles.return_value = [{"wnumber": "SN1"}]
        client = BufferedNinebotClient(
            inner, "acct", store, RequestCoalescer(), CommandAudit(store), enabled=False,
        )
        self.assertEqual(client.vehicles()[0]["wnumber"], "SN1")
        self.assertEqual(inner.vehicles.call_count, 1)
        self.assertIsNone(store.get("acct", "*", "vehicles"))
        store.close()
        temp.cleanup()


if __name__ == "__main__":
    unittest.main()
