import base64
import http.client
import json
import threading
from datetime import UTC, datetime, timedelta
from http.server import ThreadingHTTPServer
from pathlib import Path

from rns_meshtastic.config_schema import config_schema
from rns_meshtastic.gateway_config import MANAGED_FIELDS, parse_env_file
from rns_meshtastic.gateway_console import ConsoleHandler, GatewayState, _prometheus


def test_console_status_separates_lora_lan_public_and_lxmd(monkeypatch, tmp_path: Path):
    traffic = {
        "transport_id": "abc",
        "transport_uptime": 12,
        "lora": {"rx": 10, "tx": 20, "rxs": 1, "txs": 2},
        "private_tcp": {"rx": 30, "tx": 40, "rxs": 3, "txs": 4},
        "public": {"rx": 50, "tx": 60, "rxs": 5, "txs": 6},
        "public_interfaces": {"Public boundary 1": {"up": True}},
        "private_tcp_client_count": 1,
        "lan_clients": [
            {
                "session_id": "session-a",
                "source_ip": "192.0.2.44",
                "source_port": 54321,
                "up": True,
                "rx": 30,
                "tx": 40,
                "rxs": 3,
                "txs": 4,
            }
        ],
        "lan_policy": {"blocked_connections": 2, "deny_networks": ["192.0.2.9/32"]},
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
            "client_activity": {"messages_received": 7, "messages_served": 11},
            "peering": {"total": 2, "active": 1, "peers": []},
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
    assert status["lan_clients"][0]["source_ip"] == "192.0.2.44"
    assert status["lan_clients"][0]["first_observed_at"]
    assert status["lan_policy"]["blocked_connections"] == 2
    assert 'network="lora",direction="tx"} 20' in _prometheus(status)
    assert "rns_meshtastic_lan_connections_rejected_total 2" in _prometheus(status)
    assert "rns_meshtastic_lxmd_client_messages_received_total 7" in _prometheus(status)
    assert "rns_meshtastic_lxmd_peers_active 1" in _prometheus(status)


def test_console_configuration_view_redacts_secrets(monkeypatch, tmp_path: Path):
    monkeypatch.setenv("RNS_RADIO_IFAC_PASSPHRASE", "never-return-this")
    state = GatewayState(tmp_path, tmp_path / "lxmd", tmp_path / "stages")
    assert "never-return-this" not in str(state.active_environment())
    assert state.active_environment()["RNS_RADIO_IFAC_PASSPHRASE"] == "<configured>"


def test_discovery_status_exposes_safe_catalogue_and_bootstrap_state(monkeypatch, tmp_path: Path):
    monkeypatch.setenv("RNS_PUBLIC_DISCOVERY", "trusted_auto")
    monkeypatch.setenv("RNS_DISCOVERY_SOURCES", "1" * 32)
    monkeypatch.setenv("RNS_DISCOVERY_MAX", "3")
    monkeypatch.setenv("RNS_PUBLIC_UPSTREAMS", "seed.example:4242")
    monkeypatch.setenv("RNS_PUBLIC_BOOTSTRAP_UPSTREAMS", "seed.example:4242")
    monkeypatch.setattr(
        "rns_meshtastic.gateway_console.collect_discovery",
        lambda _: [
            {
                "name": "Trusted Backbone",
                "type": "BackboneInterface",
                "status": "available",
                "transport": True,
                "network_id": "1" * 32,
                "transport_id": "2" * 32,
                "reachable_on": "rns.example",
                "port": 4242,
                "last_heard": 1.0,
                "value": 18,
                "hops": 1,
                # These must never escape in a browser/API response.
                "ifac_netkey": "remote-secret",
                "config_entry": "passphrase = remote-secret",
            }
        ],
    )
    state = GatewayState(tmp_path, tmp_path / "lxmd", tmp_path / "stages")
    discovery = state._discovery_status(
        {
            "autoconnected_interfaces": [
                {"name": "Trusted Backbone", "source": "1" * 32, "up": True}
            ]
        }
    )
    assert discovery["mode"] == "trusted_auto"
    assert discovery["autoconnect"]["maximum"] == 3
    assert discovery["bootstrap"]["public"] == [{"endpoint": "seed.example:4242", "bootstrap": True}]
    candidate = discovery["candidates"][0]
    assert candidate["endpoint"] == "rns.example:4242"
    assert candidate["network_id"] == "1" * 32
    assert "remote-secret" not in str(discovery)
    assert "config_entry" not in candidate
    assert "ifac_netkey" not in candidate


def test_auto_discovery_status_has_no_identity_allowlist(monkeypatch, tmp_path: Path):
    monkeypatch.setenv("RNS_PUBLIC_DISCOVERY", "auto")
    monkeypatch.setenv("RNS_DISCOVERY_MAX", "5")
    monkeypatch.delenv("RNS_DISCOVERY_SOURCES", raising=False)
    monkeypatch.setattr("rns_meshtastic.gateway_console.collect_discovery", lambda _: [])
    state = GatewayState(tmp_path, tmp_path / "lxmd", tmp_path / "stages")
    discovery = state._discovery_status({"autoconnected_interfaces": []})
    assert discovery["mode"] == "auto"
    assert discovery["trusted_sources"] == []
    assert discovery["autoconnect"] == {"enabled": True, "maximum": 5, "active": [], "gravity": 0}


def test_discovery_status_reports_collector_failure_instead_of_empty_catalogue(monkeypatch, tmp_path: Path):
    monkeypatch.setenv("RNS_PUBLIC_DISCOVERY", "manual")

    def unavailable(_: Path):
        raise RuntimeError("shared instance unavailable")

    monkeypatch.setattr("rns_meshtastic.gateway_console.collect_discovery", unavailable)
    state = GatewayState(tmp_path, tmp_path / "lxmd", tmp_path / "stages")
    status = state._discovery_status({})
    assert status["collector"] == {"available": False, "error": "shared instance unavailable"}
    assert status["candidates"] == []


def test_console_schema_covers_all_managed_fields_without_secret_values():
    value = config_schema()
    assert value["schema"] == 2
    assert tuple(field["name"] for field in value["fields"]) == MANAGED_FIELDS
    secret_fields = [field for field in value["fields"] if field["secret"]]
    assert secret_fields
    assert all("value" not in field for field in secret_fields)
    assert value["policy_profiles"]["conservative"]["RNS_RADIO_TX_INTERVAL"] == "2.0"
    gateway_node = next(field for field in value["fields"] if field["name"] == "RNS_GATEWAY_NODE")
    assert gateway_node["applies_when"] == [
        {"field": "RNS_MESH_MODE", "values": ["gateway_unicast"]},
        {"field": "RNS_GATEWAY_ROLE", "values": ["client"]},
    ]


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


def test_heartbeat_aware_stale_alert_distinguishes_quiet_from_dead(monkeypatch, tmp_path: Path):
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
        "captured_at": (datetime.now(UTC) - timedelta(seconds=25)).isoformat(),
        "heartbeat_interval_seconds": 10,
        "online": True,
        "last_radio_activity_at": (datetime.now(UTC) - timedelta(hours=1)).isoformat(),
        "peers": [
            {
                "node_id": "!a1b3b3b8",
                "idle_seconds": 3600,
                "announce_delivery_state": "ordinary_announces_suppressed",
                "ordinary_announces_suppressed": 3,
            }
        ],
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
    status = state.status()
    assert "meshtastic_telemetry_stale" not in {alert["code"] for alert in status["alerts"]}
    assert status["radio_state"] == "up"
    assert status["radio_activity"]["state"] == "idle"
    assert status["meshtastic_peer_health"][0]["announce_delivery_state"] == ("ordinary_announces_suppressed")

    telemetry["captured_at"] = (datetime.now(UTC) - timedelta(seconds=31)).isoformat()
    telemetry_path.write_text(__import__("json").dumps(telemetry), encoding="utf-8")
    assert "meshtastic_telemetry_stale" in {alert["code"] for alert in state.status()["alerts"]}


def test_console_basic_auth_protects_ui_but_not_minimal_health():
    class State:
        @staticmethod
        def status():
            raise AssertionError("minimal health must not run expensive collectors")

    server = ThreadingHTTPServer(("127.0.0.1", 0), ConsoleHandler)
    server.gateway_state = State()
    server.console_auth_mode = "basic"
    server.console_username = "operator"
    server.console_password = "correct-horse-battery-staple"
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        connection = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=2)
        connection.request("GET", "/healthz")
        assert connection.getresponse().status == 200
        connection.request("GET", "/")
        assert connection.getresponse().status == 401
        credentials = base64.b64encode(b"operator:correct-horse-battery-staple").decode()
        connection.request("GET", "/", headers={"Authorization": f"Basic {credentials}"})
        assert connection.getresponse().status == 200
        connection.close()
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=2)


def test_apply_endpoint_is_same_origin_and_only_passes_stage_id():
    class State:
        @staticmethod
        def request_apply(stage_id):
            return {"state": "queued", "stage_id": stage_id, "request_id": "0123456789abcdef"}

    server = ThreadingHTTPServer(("127.0.0.1", 0), ConsoleHandler)
    server.gateway_state = State()
    server.console_auth_mode = "off"
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        connection = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=2)
        body = json.dumps({"stage_id": "gateway-20260903T120000.123456Z.env"})
        headers = {"Content-Type": "application/json", "Origin": "https://attacker.example"}
        connection.request("POST", "/api/v1/config/apply", body=body, headers=headers)
        response = connection.getresponse()
        assert response.status == 403
        response.read()

        origin = f"http://127.0.0.1:{server.server_port}"
        headers["Origin"] = origin
        connection.request("POST", "/api/v1/config/apply", body=body, headers=headers)
        response = connection.getresponse()
        assert response.status == 202
        assert json.loads(response.read())["state"] == "queued"
        connection.close()
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=2)
