import pytest

from rns_meshtastic.contracts import (
    BridgeAlertV1,
    BridgeCapabilitiesV1,
    TrafficCounterV1,
    to_dict,
)


def test_contracts_are_explicitly_versioned_and_json_ready():
    value = to_dict(
        BridgeCapabilitiesV1(
            implementation="test",
            implementation_version="1.0",
            rns_tcp_port=7822,
            status_api_port=7823,
        )
    )
    assert value["schema"] == 1
    assert value["constrained_transport"] is True
    assert value["realtime_supported"] is False
    assert "auto_multi_peer" in value["addressing_modes"]


def test_traffic_counter_defaults_are_zero():
    value = to_dict(TrafficCounterV1())
    assert value["tx_bytes"] == 0
    assert value["available"] is True
    assert value["source"] == "reticulum"


def test_invalid_alert_severity_is_rejected():
    with pytest.raises(ValueError, match="severity"):
        BridgeAlertV1("fatal", "example", "invalid")
