from pathlib import Path

import pytest

from rns_meshtastic.event_journal import EventJournal
from rns_meshtastic.lxmd_control import LXMDAnnounceRateLimited, LXMDController


class _Router:
    propagation_node = True

    def __init__(self):
        self.announces = 0

    def compile_stats(self):
        return {
            "identity_hash": bytes.fromhex("11" * 16),
            "destination_hash": bytes.fromhex("22" * 16),
            "uptime": 12,
            "messagestore": {"count": 2, "bytes": 1024, "limit": 4096},
            "total_peers": 1,
            "unpeered_propagation_rx_bytes": 30,
            "peers": {bytes.fromhex("33" * 16): {"rx_bytes": 70, "tx_bytes": 80}},
        }

    def announce_propagation_node(self):
        self.announces += 1


def test_lxmd_controller_reports_real_peer_traffic_and_rate_limits(tmp_path: Path):
    router = _Router()
    controller = LXMDController(
        lambda: router,
        cooldown_seconds=300,
        state_file=tmp_path / "announce.json",
        events=EventJournal(tmp_path / "events.jsonl"),
    )
    status = controller.status()
    assert status["destination_hash"] == "22" * 16
    assert status["traffic"]["rx_bytes"] == 100
    assert status["traffic"]["tx_bytes"] == 80
    assert status["store_utilisation_percent"] == 25.0

    first = controller.announce()
    assert router.announces == 1
    assert first["next_allowed_at"] > first["announced_at"]
    event = controller.events.read()["events"][0]
    assert event["code"] == "lxmd_announce_scheduled"
    with pytest.raises(LXMDAnnounceRateLimited):
        controller.announce()
    assert router.announces == 1


def test_lxmd_controller_derives_current_rate_from_counter_delta(monkeypatch, tmp_path: Path):
    router = _Router()
    times = iter((10.0, 12.0))
    monkeypatch.setattr("rns_meshtastic.lxmd_control.time.monotonic", lambda: next(times))
    controller = LXMDController(
        lambda: router,
        cooldown_seconds=300,
        state_file=tmp_path / "announce.json",
        events=EventJournal(tmp_path / "events.jsonl"),
    )
    controller.status()
    original = router.compile_stats

    def increased_stats():
        stats = original()
        peer = next(iter(stats["peers"].values()))
        peer["rx_bytes"] += 25
        peer["tx_bytes"] += 50
        return stats

    router.compile_stats = increased_stats
    status = controller.status()
    assert status["traffic"]["rx_bps"] == 100.0
    assert status["traffic"]["tx_bps"] == 200.0
