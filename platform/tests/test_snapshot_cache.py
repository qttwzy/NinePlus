import json
import tempfile
import threading
import time
import unittest
from datetime import datetime
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

    def test_idempotency_key_cannot_be_reused_for_a_different_command(self):
        self.audit.begin("acct", "SN1", "engine_start", "same-key")
        with self.assertRaisesRegex(ValueError, "幂等键"):
            self.audit.begin("acct", "SN1", "engine_stop", "same-key")
        with self.assertRaisesRegex(ValueError, "幂等键"):
            self.audit.begin("acct", "SN2", "engine_start", "same-key")

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

    def test_concurrent_swr_refreshes_share_one_upstream_call(self):
        from server import BufferedNinebotClient, DirectNinebotClient, Settings

        temp = tempfile.TemporaryDirectory()
        root = Path(temp.name)
        (root / "tokens.json").write_text("{}", encoding="utf-8")
        settings = Settings("", ninebot_config_dir=root)
        store = SnapshotStore(root / "snapshots.db")
        inner = MagicMock(spec=DirectNinebotClient)
        upstream_started = threading.Event()
        upstream_release = threading.Event()

        def fetch_status(_sn):
            upstream_started.set()
            self.assertTrue(upstream_release.wait(timeout=2))
            return {"dump_energy": 9, "updated_at": iso()}

        inner.status_only.side_effect = fetch_status
        client = BufferedNinebotClient(
            inner, "acct", store, RequestCoalescer(), CommandAudit(store), enabled=True,
        )
        store.put("acct", "SN1", "status", {"dump_energy": 10}, ttl_seconds=0)

        results = []
        barrier = threading.Barrier(6)

        def read_stale():
            barrier.wait(timeout=2)
            results.append(client.status_only("SN1"))

        threads = [threading.Thread(target=read_stale) for _ in range(6)]
        for thread in threads:
            thread.start()
        self.assertTrue(upstream_started.wait(timeout=2))
        for thread in threads:
            thread.join(timeout=2)
            self.assertFalse(thread.is_alive())
        upstream_release.set()

        deadline = time.time() + 2
        while time.time() < deadline and store.get("acct", "SN1", "status").stale:
            time.sleep(0.01)
        self.assertEqual(inner.status_only.call_count, 1)
        self.assertEqual(len(results), 6)
        self.assertTrue(all(result["dump_energy"] == 10 for result in results))
        store.close()
        temp.cleanup()

    def test_dashboard_marks_stale_when_any_read_is_stale(self):
        from server import BufferedNinebotClient, DirectNinebotClient, Settings

        temp = tempfile.TemporaryDirectory()
        root = Path(temp.name)
        (root / "tokens.json").write_text("{}", encoding="utf-8")
        settings = Settings("", ninebot_config_dir=root)
        store = SnapshotStore(root / "snapshots.db")
        inner = MagicMock(spec=DirectNinebotClient)
        inner.status_only.return_value = {"status": "fresh"}
        inner.battery_only.return_value = {"battery": "refreshing"}
        inner.travel.return_value = {"list": ["refreshing"]}
        inner.vehicles.return_value = [{"wnumber": "SN1"}]
        client = BufferedNinebotClient(
            inner, "acct", store, RequestCoalescer(), CommandAudit(store), enabled=True,
        )
        month = datetime.now().strftime("%Y%m")
        store.put("acct", "SN1", "status", {"status": "fresh"}, ttl_seconds=60)
        store.put("acct", "SN1", "battery", {"battery": "stale"}, ttl_seconds=0)
        store.put("acct", "SN1", f"travel:{month}", {"list": ["stale"]}, ttl_seconds=0)
        store.put("acct", "*", "vehicles", [{"wnumber": "SN1"}], ttl_seconds=60)

        payload, meta = client.dashboard("SN1")

        self.assertEqual(payload["status"]["status"], "fresh")
        self.assertEqual(payload["battery"]["battery"], "stale")
        self.assertTrue(meta["stale"])
        self.assertFalse(meta["components"]["status"]["stale"])
        self.assertTrue(meta["components"]["battery"]["stale"])
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline and any(
            store.get("acct", "SN1", kind).stale
            for kind in ("battery", f"travel:{month}")
        ):
            time.sleep(0.01)
        store.close()
        temp.cleanup()

    def test_response_metadata_is_isolated_between_threads(self):
        from server import BufferedNinebotClient, DirectNinebotClient

        with tempfile.TemporaryDirectory() as directory:
            store = SnapshotStore(Path(directory) / "snapshots.db")
            self.addCleanup(store.close)
            client = BufferedNinebotClient(
                MagicMock(spec=DirectNinebotClient), "acct", store,
                RequestCoalescer(), CommandAudit(store),
            )
            status = store.put("acct", "SN1", "status", {}, 60, "status-date")
            battery = store.put("acct", "SN1", "battery", {}, 60, "battery-date")
            status_read = threading.Event()
            battery_read = threading.Event()
            results = {}

            def read_status():
                client.status_only("SN1")
                status_read.set()
                battery_read.wait(2)
                results["status"] = client.last_meta

            def read_battery():
                status_read.wait(2)
                client.battery_only("SN1")
                battery_read.set()
                results["battery"] = client.last_meta

            threads = [threading.Thread(target=read_status), threading.Thread(target=read_battery)]
            for thread in threads:
                thread.start()
            for thread in threads:
                thread.join(2)
                self.assertFalse(thread.is_alive())
            self.assertEqual(results["status"]["fetched_at"], status.fetched_at)
            self.assertEqual(results["status"]["source_updated_at"], "status-date")
            self.assertEqual(results["battery"]["source_updated_at"], "battery-date")
            self.assertEqual(client.metrics.snapshot()["cache_hits"], 2)

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
