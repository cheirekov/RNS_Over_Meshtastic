from pathlib import Path

from rns_meshtastic.traffic_report import format_report, load_snapshot, save_snapshot, summarise_rnstatus


def status() -> dict:
    return {
        "transport_id": "transport-a",
        "transport_uptime": 100,
        "interfaces": [
            {
                "type": "RNSMeshtasticInterface",
                "name": "RNSMeshtasticInterface[Meshtastic radio hub/native/auto_multi_peer]",
                "short_name": "Meshtastic radio hub",
                "hash": "radio-parent",
                "rxb": 7,
                "txb": 100,
                "rxs": 1,
                "txs": 2,
            },
            {
                "type": "MeshtasticPeerInterface",
                "name": "MeshtasticPeerInterface[!a1b3b3b8 via Meshtastic radio hub]",
                "short_name": "Meshtastic radio hub peer !a1b3b3b8",
                "parent_interface_hash": "radio-parent",
                "rxb": 40,
                "txb": 100,
                "rxs": 3,
                "txs": 2,
            },
            {
                "type": "BackboneClientInterface",
                "name": "BackboneInterface[Public boundary 1/193.193.182.147:4242]",
                "short_name": "Public boundary 1",
                "rxb": 200,
                "txb": 50,
                "rxs": 4,
                "txs": 5,
                "status": True,
            },
            {
                "type": "TCPServerInterface",
                "name": "TCPServerInterface[LAN VPN Reticulum clients/0.0.0.0:4242]",
                "short_name": "LAN VPN Reticulum clients",
                "rxb": 300,
                "txb": 400,
                "rxs": 6,
                "txs": 7,
            },
            {
                "type": "BackboneClientInterface",
                "name": "BackboneInterface[Private boundary 1/private.example:4242]",
                "short_name": "Private boundary 1",
                "rxb": 25,
                "txb": 15,
                "rxs": 1,
                "txs": 2,
                "status": True,
            },
            {
                "type": "TCPClientInterface",
                "name": "TCPInterface[Client on LAN VPN Reticulum clients/192.0.2.10:54321]",
                "short_name": "Client on LAN VPN Reticulum clients",
                "parent_interface_name": "TCPServerInterface[LAN VPN Reticulum clients/0.0.0.0:4242]",
                "hash": "lan-session-1",
                "status": True,
                "rxb": 250,
                "txb": 350,
                "rxs": 8,
                "txs": 9,
                "incoming_announce_frequency": 0.25,
                "incoming_pr_frequency": 0.5,
            },
        ],
    }


def test_summary_does_not_double_count_dynamic_radio_tx_or_tcp_children() -> None:
    summary = summarise_rnstatus(status())
    assert summary["lora"] == {"rx": 47, "tx": 100, "rxs": 4.0, "txs": 2.0}
    assert summary["public"] == {"rx": 200, "tx": 50, "rxs": 4.0, "txs": 5.0}
    assert summary["private_tcp"] == {"rx": 300, "tx": 400, "rxs": 6.0, "txs": 7.0}
    assert summary["private_upstream"] == {"rx": 25, "tx": 15, "rxs": 1.0, "txs": 2.0}
    assert summary["radio_parent_count"] == 1
    assert summary["radio_peer_count"] == 1
    assert summary["private_tcp_client_count"] == 1
    assert summary["lan_clients"] == [
        {
            "session_id": "lan-session-1",
            "source_ip": "192.0.2.10",
            "source_port": 54321,
            "up": True,
            "rx": 250,
            "tx": 350,
            "rxs": 8.0,
            "txs": 9.0,
            "incoming_announce_frequency": 0.25,
            "incoming_path_request_frequency": 0.5,
        }
    ]


def test_snapshot_round_trip_is_private(tmp_path: Path) -> None:
    snapshot = summarise_rnstatus(status())
    path = tmp_path / "baseline.json"
    save_snapshot(path, snapshot)
    assert load_snapshot(path) == snapshot
    assert path.stat().st_mode & 0o777 == 0o600


def test_report_calculates_window_delta_without_double_counting() -> None:
    baseline = summarise_rnstatus(status())
    baseline["captured_at"] = "2026-08-27T12:00:00+00:00"
    baseline["transport_uptime"] = 100
    current_status = status()
    current_status["transport_uptime"] = 160
    current_status["interfaces"][0]["txb"] = 160
    current_status["interfaces"][1]["txb"] = 160
    current_status["interfaces"][1]["rxb"] = 70
    current = summarise_rnstatus(current_status)
    current["captured_at"] = "2026-08-27T12:01:00+00:00"
    report = format_report(current, baseline)
    assert "window: 60s" in report
    assert "window +30 B (4.0 bps average)" in report
    assert "window +60 B (8.0 bps average)" in report
    assert "not RF airtime" in report


def test_report_rejects_delta_across_transport_restart() -> None:
    baseline = summarise_rnstatus(status())
    current = summarise_rnstatus(status() | {"transport_id": "transport-b"})
    report = format_report(current, baseline)
    assert "different/restarted Transport" in report
    assert "window +" not in report
