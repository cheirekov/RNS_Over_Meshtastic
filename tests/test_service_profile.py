from pathlib import Path

import pytest

from rns_meshtastic.service_profile import configuration_values, render_service_profile


def environment() -> dict[str, str]:
    return {
        "MESHTASTIC_TCP_HOST": "192.0.2.10",
        "RNS_ALLOWED_NODES": "!a1b3b3b8, !8fd13c64",
        "RNS_RADIO_IFAC_NAME": "radio-private",
        "RNS_RADIO_IFAC_PASSPHRASE": "radio-secret-value",
        "RNS_TCP_IFAC_NAME": "vpn-private",
        "RNS_TCP_IFAC_PASSPHRASE": "vpn-secret-value",
    }


def test_renders_restricted_gateway_and_bounded_lxmd(tmp_path: Path) -> None:
    templates = Path(__file__).parents[1] / "docker" / "linux-service" / "templates"
    rns = tmp_path / "rns"
    lxmd = tmp_path / "lxmd"
    render_service_profile(templates, rns, lxmd, environment())

    rns_config = (rns / "config").read_text()
    lxmd_config = (lxmd / "config").read_text()
    assert "tcp_host = 192.0.2.10" in rns_config
    assert "mesh_mode = gateway_unicast" in rns_config
    assert "allowed_nodes = !a1b3b3b8, !8fd13c64" in rns_config
    assert "network_name = radio-private" in rns_config
    assert "autopeer = no" in lxmd_config
    assert "message_storage_limit = 64" in lxmd_config
    assert "propagation_message_max_accepted_size = 8" in lxmd_config
    assert "propagation_sync_max_accepted_size = 64" in lxmd_config
    assert not (lxmd / "allowed").exists()
    assert (rns / "config").stat().st_mode & 0o777 == 0o600


def test_requires_allowlist_for_unicast_hub() -> None:
    values = environment()
    values["RNS_ALLOWED_NODES"] = ""
    with pytest.raises(ValueError, match="RNS_ALLOWED_NODES"):
        configuration_values(values)


def test_rejects_reused_ifac_credentials() -> None:
    values = environment()
    values["RNS_TCP_IFAC_NAME"] = values["RNS_RADIO_IFAC_NAME"]
    values["RNS_TCP_IFAC_PASSPHRASE"] = values["RNS_RADIO_IFAC_PASSPHRASE"]
    with pytest.raises(ValueError, match="must be different"):
        configuration_values(values)


def test_ifacs_can_both_be_disabled(tmp_path: Path) -> None:
    templates = Path(__file__).parents[1] / "docker" / "linux-service" / "templates"
    values = environment() | {
        "RNS_RADIO_IFAC_NAME": "",
        "RNS_RADIO_IFAC_PASSPHRASE": "",
        "RNS_TCP_IFAC_NAME": "",
        "RNS_TCP_IFAC_PASSPHRASE": "",
    }
    render_service_profile(templates, tmp_path / "rns", tmp_path / "lxmd", values)
    config = (tmp_path / "rns" / "config").read_text()
    assert "ifac_size" not in config
    assert "network_name" not in config
    assert "passphrase" not in config
    assert "IFAC disabled for the radio interface" in config
    assert "IFAC disabled for the TCP interface" in config


def test_radio_and_tcp_ifacs_are_independently_optional(tmp_path: Path) -> None:
    templates = Path(__file__).parents[1] / "docker" / "linux-service" / "templates"
    values = environment() | {
        "RNS_RADIO_IFAC_NAME": "",
        "RNS_RADIO_IFAC_PASSPHRASE": "",
    }
    render_service_profile(templates, tmp_path / "rns", tmp_path / "lxmd", values)
    config = (tmp_path / "rns" / "config").read_text()
    assert "IFAC disabled for the radio interface" in config
    assert "network_name = vpn-private" in config


@pytest.mark.parametrize(
    ("missing", "present"),
    [
        ("RNS_RADIO_IFAC_NAME", "RNS_RADIO_IFAC_PASSPHRASE"),
        ("RNS_RADIO_IFAC_PASSPHRASE", "RNS_RADIO_IFAC_NAME"),
        ("RNS_TCP_IFAC_NAME", "RNS_TCP_IFAC_PASSPHRASE"),
        ("RNS_TCP_IFAC_PASSPHRASE", "RNS_TCP_IFAC_NAME"),
    ],
)
def test_rejects_partial_ifac_pair(missing: str, present: str) -> None:
    values = environment()
    values[missing] = ""
    assert values[present]
    with pytest.raises(ValueError, match="must both be set or both be empty"):
        configuration_values(values)


def test_authenticated_lxmd_writes_valid_allowlist(tmp_path: Path) -> None:
    templates = Path(__file__).parents[1] / "docker" / "linux-service" / "templates"
    values = environment() | {
        "LXMD_AUTH_REQUIRED": "yes",
        "LXMD_ALLOWED_IDENTITIES": "0123456789abcdef0123456789ABCDEF",
    }
    render_service_profile(templates, tmp_path / "rns", tmp_path / "lxmd", values)
    assert (tmp_path / "lxmd" / "allowed").read_text() == (
        "0123456789abcdef0123456789abcdef\n"
    )


def test_unicast_client_requires_and_renders_gateway_node(tmp_path: Path) -> None:
    templates = Path(__file__).parents[1] / "docker" / "linux-service" / "templates"
    values = environment() | {
        "RNS_GATEWAY_ROLE": "client",
        "RNS_ALLOWED_NODES": "",
    }
    with pytest.raises(ValueError, match="RNS_GATEWAY_NODE"):
        configuration_values(values)

    values["RNS_GATEWAY_NODE"] = "!8fd1336c"
    render_service_profile(templates, tmp_path / "rns", tmp_path / "lxmd", values)
    assert "gateway_node = !8fd1336c" in (tmp_path / "rns" / "config").read_text()


def test_broadcast_requires_client_role() -> None:
    values = environment() | {"RNS_MESH_MODE": "broadcast"}
    with pytest.raises(ValueError, match="must be client in broadcast mode"):
        configuration_values(values)
