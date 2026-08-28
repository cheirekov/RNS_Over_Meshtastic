from datetime import UTC, datetime
from pathlib import Path

from rns_meshtastic.config_schema import config_schema
from rns_meshtastic.gateway_config import MANAGED_FIELDS, parse_env_file
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
    (tmp_path / "meshtastic-telemetry.json").write_text(__import__("json").dumps(telemetry), encoding="utf-8")
    monkeypatch.setattr("rns_meshtastic.gateway_console.collect_rnstatus", lambda _: traffic)
    monkeypatch.setattr("rns_meshtastic.gateway_console.collect_discovery", lambda _: [])
    monkeypatch.setattr(
        "rns_meshtastic.gateway_console.collect_routes",
        lambda _: [{"hash": "1" * 32, "interface": "peer !a1b3b3b8", "hops": 1}],
    )
    monkeypatch.setattr(
        "rns_meshtastic.gateway_console.collect_lxmd_status",
        lambda *_: {
            "up": True,
            "destination_hash": "0" * 32,
            "traffic": {
                "rx_bytes": 70,
                "tx_bytes": 80,
                "available": True,
                "source": "lxmd.compile_stats.peer_bytes",
            },
        },
    )

    status = GatewayState(tmp_path, tmp_path / "lxmd", tmp_path / "stages").status()
    assert status["traffic"]["lora"]["tx_bytes"] == 20
    assert status["traffic"]["lan"]["rx_bytes"] == 30
    assert status["traffic"]["public"]["tx_bytes"] == 60
    assert status["peers"][0]["peer"] == "!a1b3b3b8"
    assert status["peers"][0]["routes"] == 1
    assert status["public_interfaces"]["Public boundary 1"]["reconnects"] == 0
    assert status["traffic"]["propagation"]["rx_bytes"] == 70
    assert status["traffic"]["propagation"]["available"] is True
    assert 'network="lora",direction="tx"} 20' in _prometheus(status)


def test_console_configuration_view_redacts_secrets(monkeypatch, tmp_path: Path):
    monkeypatch.setenv("RNS_RADIO_IFAC_PASSPHRASE", "never-return-this")
    state = GatewayState(tmp_path, tmp_path / "lxmd", tmp_path / "stages")
    assert "never-return-this" not in str(state.active_environment())
    assert state.active_environment()["RNS_RADIO_IFAC_PASSPHRASE"] == "<configured>"


def test_console_schema_covers_all_managed_fields_without_secret_values():
    value = config_schema()
    assert tuple(field["name"] for field in value["fields"]) == MANAGED_FIELDS
    secret_fields = [field for field in value["fields"] if field["secret"]]
    assert secret_fields
    assert all("value" not in field for field in secret_fields)


def test_console_stage_preserves_existing_secret(monkeypatch, tmp_path: Path):
    monkeypatch.setenv("MESHTASTIC_TCP_HOST", "192.0.2.10")
    monkeypatch.setenv("RNS_MESH_MODE", "auto_multi_peer")
    monkeypatch.setenv("RNS_GATEWAY_ROLE", "hub")
    monkeypatch.setenv("RNS_RADIO_IFAC_NAME", "radio")
    monkeypatch.setenv("RNS_RADIO_IFAC_PASSPHRASE", "keep-this-secret")
    state = GatewayState(tmp_path, tmp_path / "lxmd", tmp_path / "stages")
    result = state.stage({"MESHTASTIC_HOP_LIMIT": "2"})
    staged = parse_env_file(tmp_path / "stages" / result["stage_id"])
    assert staged["RNS_RADIO_IFAC_PASSPHRASE"] == "keep-this-secret"


def test_unavailable_lxmd_traffic_is_not_exported_as_zero_metric(monkeypatch, tmp_path: Path):
    traffic = {
        "transport_id": "abc",
        "transport_uptime": 12,
        "lora": {},
        "private_tcp": {},
        "public": {},
        "public_interfaces": {},
    }
    monkeypatch.setattr("rns_meshtastic.gateway_console.collect_rnstatus", lambda _: traffic)
    monkeypatch.setattr("rns_meshtastic.gateway_console.collect_discovery", lambda _: [])
    monkeypatch.setattr("rns_meshtastic.gateway_console.collect_routes", lambda _: [])
    monkeypatch.setattr(
        "rns_meshtastic.gateway_console.collect_lxmd_status",
        lambda *_: {"up": True, "traffic": {"available": False, "source": "unavailable"}},
    )
    state = GatewayState(tmp_path, tmp_path / "lxmd", tmp_path / "stages")
    status = state.status()
    assert status["traffic"]["propagation"]["available"] is False
    metrics = _prometheus(status)
    assert 'traffic_available{network="propagation"} 0' in metrics
    assert 'traffic_bytes_total{network="propagation"' not in metrics


def test_cumulative_history_does_not_create_alert_but_new_delta_does(monkeypatch, tmp_path: Path):
    traffic = {
        "transport_id": "abc",
        "transport_uptime": 12,
        "lora": {},
        "private_tcp": {},
        "public": {},
        "public_interfaces": {},
    }
    telemetry = {
        "schema": 1,
        "captured_at": datetime.now(UTC).isoformat(),
        "online": True,
        "counters": {"tx_frames_rejected": 4, "send_failures": 0, "backend_down": 0},
        "reassembly": {"repair_throttled": 0, "assemblies_expired": 0, "capped_repairs": 0},
    }
    telemetry_path = tmp_path / "meshtastic-telemetry.json"
    telemetry_path.write_text(__import__("json").dumps(telemetry), encoding="utf-8")
    monkeypatch.setattr("rns_meshtastic.gateway_console.collect_rnstatus", lambda _: traffic)
    monkeypatch.setattr("rns_meshtastic.gateway_console.collect_routes", lambda _: [])
    monkeypatch.setattr("rns_meshtastic.gateway_console.collect_discovery", lambda _: [])
    monkeypatch.setattr(
        "rns_meshtastic.gateway_console.collect_lxmd_status",
        lambda *_: {"up": True, "traffic": {"available": False, "source": "unavailable"}},
    )
    state = GatewayState(tmp_path, tmp_path / "lxmd", tmp_path / "stages")
    assert "lora_tx_rejected" not in {alert["code"] for alert in state.status()["alerts"]}
    telemetry["counters"]["tx_frames_rejected"] = 5
    telemetry["captured_at"] = datetime.now(UTC).isoformat()
    telemetry_path.write_text(__import__("json").dumps(telemetry), encoding="utf-8")
    assert "lora_tx_rejected" in {alert["code"] for alert in state.status()["alerts"]}
    state._transient_alerts.clear()
    assert "lora_tx_rejected" not in {alert["code"] for alert in state.status()["alerts"]}
