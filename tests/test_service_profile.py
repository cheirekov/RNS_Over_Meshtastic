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
    assert "accept_broadcast_on_hub = No" in rns_config
    assert "max_peers = 32" in rns_config
    assert "allowed_nodes = !a1b3b3b8, !8fd13c64" in rns_config
    assert "mode = gateway" in rns_config
    assert "No public boundary upstreams configured" in rns_config
    assert "network_name = radio-private" in rns_config
    assert "autopeer = no" in lxmd_config
    assert "from_static_only = yes" in lxmd_config
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


def test_auto_multi_peer_allows_open_discovery_and_renders_bounded_hub(tmp_path: Path) -> None:
    templates = Path(__file__).parents[1] / "docker" / "linux-service" / "templates"
    values = environment() | {
        "RNS_MESH_MODE": "auto_multi_peer",
        "RNS_ALLOWED_NODES": "",
        "RNS_MAX_PEERS": "12",
    }

    render_service_profile(templates, tmp_path / "rns", tmp_path / "lxmd", values)
    config = (tmp_path / "rns" / "config").read_text()
    assert "mesh_mode = auto_multi_peer" in config
    assert "gateway_role = hub" in config
    assert "accept_broadcast_on_hub = Yes" in config
    assert "max_peers = 12" in config
    assert "allowed_nodes is intentionally empty" in config


def test_auto_multi_peer_can_limit_discovery_with_allowlist(tmp_path: Path) -> None:
    templates = Path(__file__).parents[1] / "docker" / "linux-service" / "templates"
    values = environment() | {"RNS_MESH_MODE": "auto_multi_peer"}
    render_service_profile(templates, tmp_path / "rns", tmp_path / "lxmd", values)
    config = (tmp_path / "rns" / "config").read_text()
    assert "accept_broadcast_on_hub = Yes" in config
    assert "allowed_nodes = !a1b3b3b8, !8fd13c64" in config


def test_public_upstreams_render_as_boundaries_and_make_private_interfaces_internal(
    tmp_path: Path,
) -> None:
    templates = Path(__file__).parents[1] / "docker" / "linux-service" / "templates"
    values = environment() | {
        "RNS_PUBLIC_UPSTREAMS": "193.193.182.147:4242,[2001:db8::1]:4243",
    }
    render_service_profile(templates, tmp_path / "rns", tmp_path / "lxmd", values)
    config = (tmp_path / "rns" / "config").read_text()
    assert config.count("mode = internal") == 2
    assert config.count("mode = boundary") == 2
    assert "target_host = 193.193.182.147" in config
    assert "target_port = 4242" in config
    assert "target_host = 2001:db8::1" in config
    assert "target_port = 4243" in config
    assert config.count("announces_from_internal = No") == 2


def test_lan_public_visibility_keeps_radio_internal_and_makes_lan_gateway(
    tmp_path: Path,
) -> None:
    templates = Path(__file__).parents[1] / "docker" / "linux-service" / "templates"
    values = environment() | {
        "RNS_PUBLIC_UPSTREAMS": "193.193.182.147:4242",
        "RNS_LAN_PUBLIC_VISIBILITY": "yes",
    }
    render_service_profile(templates, tmp_path / "rns", tmp_path / "lxmd", values)
    config = (tmp_path / "rns" / "config").read_text()
    radio, remainder = config.split("  [[LAN VPN Reticulum clients]]", maxsplit=1)
    lan, public = remainder.split("  [[Public boundary 1]]", maxsplit=1)
    assert "mode = internal" in radio
    assert "mode = gateway" in lan
    assert "mode = boundary" in public
    assert "announces_from_internal = No" in public


def test_trusted_discovery_is_boundary_only_and_keeps_radio_internal(tmp_path: Path) -> None:
    templates = Path(__file__).parents[1] / "docker" / "linux-service" / "templates"
    values = environment() | {
        "RNS_PUBLIC_DISCOVERY": "trusted_auto",
        "RNS_DISCOVERY_SOURCES": "0123456789abcdef0123456789abcdef",
        "RNS_DISCOVERY_MAX": "2",
    }
    render_service_profile(templates, tmp_path / "rns", tmp_path / "lxmd", values)
    config = (tmp_path / "rns" / "config").read_text()
    radio, lan = config.split("  [[LAN VPN Reticulum clients]]", maxsplit=1)
    assert "discover_interfaces = Yes" in config
    assert "autoconnect_discovered_interfaces = 2" in config
    assert "autoconnect_interface_mode = boundary" in config
    assert "autoconnect_announces_to_internal = No" in config
    assert "mode = internal" in radio
    assert "mode = internal" in lan


@pytest.mark.parametrize("visibility", ["true", "1", "enabled", "maybe"])
def test_rejects_invalid_lan_public_visibility(visibility: str) -> None:
    values = environment() | {"RNS_LAN_PUBLIC_VISIBILITY": visibility}
    with pytest.raises(ValueError, match="RNS_LAN_PUBLIC_VISIBILITY must be yes or no"):
        configuration_values(values)


@pytest.mark.parametrize(
    "upstreams",
    [
        "missing-port",
        "host.example:not-a-port",
        "host_name.example:4242",
        "user@host.example:4242",
        "[2001:db8::1:4242",
        "host.example:0",
        "host.example:65536",
        "999.999.999.999:4242",
        "host.example:4242,",
        "host.example:4242,host.example:4242",
    ],
)
def test_rejects_invalid_public_upstreams(upstreams: str) -> None:
    values = environment() | {"RNS_PUBLIC_UPSTREAMS": upstreams}
    with pytest.raises(ValueError, match="RNS_PUBLIC_UPSTREAMS"):
        configuration_values(values)


def test_rejects_more_than_eight_public_upstreams() -> None:
    upstreams = ",".join(f"192.0.2.{index}:4242" for index in range(1, 10))
    values = environment() | {"RNS_PUBLIC_UPSTREAMS": upstreams}
    with pytest.raises(ValueError, match="at most 8"):
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


def test_auto_multi_peer_requires_hub_role() -> None:
    values = environment() | {
        "RNS_MESH_MODE": "auto_multi_peer",
        "RNS_GATEWAY_ROLE": "client",
    }
    with pytest.raises(ValueError, match="must be hub"):
        configuration_values(values)


@pytest.mark.parametrize("max_peers", ["0", "513"])
def test_rejects_out_of_range_auto_multi_peer_limit(max_peers: str) -> None:
    values = environment() | {
        "RNS_MESH_MODE": "auto_multi_peer",
        "RNS_MAX_PEERS": max_peers,
    }
    with pytest.raises(ValueError, match="RNS_MAX_PEERS must be between 1 and 512"):
        configuration_values(values)
