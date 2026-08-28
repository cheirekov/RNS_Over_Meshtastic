from pathlib import Path

from rns_meshtastic.gateway_console import GatewayState, _prometheus


def test_console_status_separates_lora_lan_public_and_lxmd(monkeypatch, tmp_path: Path):
    traffic = {
        "transport_id": "abc",
        "transport_uptime": 12,
        "lora": {"rx": 10, "tx": 20, "rxs": 1, "txs": 2},
        "private_tcp": {"rx": 30, "tx": 40, "rxs": 3, "txs": 4},
        "public": {"rx": 50, "tx": 60, "rxs": 5, "txs": 6},
        "public_interfaces": {"Public boundary 1": {"up": True}},
    }
    telemetry = {
        "schema": 1,
        "online": True,
        "mesh_mode": "auto_multi_peer",
        "queue": {"fragments": 1, "limit": 32},
        "peers": [{"node_id": "!a1b3b3b8"}],
    }
    (tmp_path / "meshtastic-telemetry.json").write_text(
        __import__("json").dumps(telemetry), encoding="utf-8"
    )
    monkeypatch.setattr("rns_meshtastic.gateway_console.collect_rnstatus", lambda _: traffic)
    monkeypatch.setattr("rns_meshtastic.gateway_console.collect_discovery", lambda _: [])
    monkeypatch.setattr(
        "rns_meshtastic.gateway_console.collect_routes",
        lambda _: [{"hash": "1" * 32, "interface": "peer !a1b3b3b8", "hops": 1}],
    )
    monkeypatch.setattr(
        "rns_meshtastic.gateway_console.collect_lxmd_status",
        lambda *_: {"up": True, "destination_hash": "0" * 32},
    )

    status = GatewayState(tmp_path, tmp_path / "lxmd", tmp_path / "stages").status()
    assert status["traffic"]["lora"]["tx_bytes"] == 20
    assert status["traffic"]["lan"]["rx_bytes"] == 30
    assert status["traffic"]["public"]["tx_bytes"] == 60
    assert status["peers"][0]["peer"] == "!a1b3b3b8"
    assert status["peers"][0]["routes"] == 1
    assert status["public_interfaces"]["Public boundary 1"]["reconnects"] == 0
    assert "network=\"lora\",direction=\"tx\"} 20" in _prometheus(status)


def test_console_configuration_view_redacts_secrets(monkeypatch, tmp_path: Path):
    monkeypatch.setenv("RNS_RADIO_IFAC_PASSPHRASE", "never-return-this")
    state = GatewayState(tmp_path, tmp_path / "lxmd", tmp_path / "stages")
    assert "never-return-this" not in str(state.active_environment())
