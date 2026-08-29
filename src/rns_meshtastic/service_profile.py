from __future__ import annotations

import argparse
import ipaddress
import os
import re
import tempfile
from collections.abc import Mapping
from pathlib import Path
from string import Template

NODE_ID = re.compile(r"![0-9a-fA-F]{8}")
IDENTITY_HASH = re.compile(r"[0-9a-fA-F]{32}")
HOSTNAME = re.compile(
    r"(?=.{1,253}\Z)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)*"
    r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
)
MAX_PUBLIC_UPSTREAMS = 8


def _value(environment: Mapping[str, str], name: str, default: str | None = None) -> str:
    value = environment.get(name, default)
    if value is None or not value.strip():
        raise ValueError(f"{name} is required")
    value = value.strip()
    if "\n" in value or "\r" in value:
        raise ValueError(f"{name} must be a single line")
    if "#" in value or ";" in value:
        raise ValueError(f"{name} cannot contain config comment characters # or ;")
    return value


def _integer(
    environment: Mapping[str, str], name: str, default: int, minimum: int, maximum: int
) -> int:
    try:
        value = int(_value(environment, name, str(default)))
    except ValueError as error:
        raise ValueError(f"{name} must be an integer") from error
    if value < minimum or value > maximum:
        raise ValueError(f"{name} must be between {minimum} and {maximum}")
    return value


def _float_value(
    environment: Mapping[str, str], name: str, default: float, minimum: float, maximum: float
) -> float:
    try:
        value = float(_value(environment, name, str(default)))
    except ValueError as error:
        raise ValueError(f"{name} must be a number") from error
    if value < minimum or value > maximum:
        raise ValueError(f"{name} must be between {minimum} and {maximum}")
    return value


def _yes_no(environment: Mapping[str, str], name: str, default: str) -> str:
    value = _value(environment, name, default).lower()
    if value not in {"yes", "no"}:
        raise ValueError(f"{name} must be yes or no")
    return value


def _optional_value(environment: Mapping[str, str], name: str) -> str:
    if not environment.get(name, "").strip():
        return ""
    return _value(environment, name)


def _validate_upstream_host(host: str, variable: str) -> str:
    if not host:
        raise ValueError(f"{variable} contains an empty host")
    try:
        ipaddress.ip_address(host)
    except ValueError:
        if "." in host and host.replace(".", "").isdigit():
            raise ValueError(f"{variable} contains an invalid IP address") from None
        if HOSTNAME.fullmatch(host) is None:
            raise ValueError(f"{variable} hosts must be IP addresses or valid DNS names") from None
    return host


def _upstream_endpoint(value: str, variable: str) -> tuple[str, int]:
    value = value.strip()
    if not value:
        raise ValueError(f"{variable} cannot contain empty entries")
    if any(character in value for character in "\r\n#;/@"):
        raise ValueError(f"{variable} contains an unsafe endpoint")

    if value.startswith("["):
        closing = value.find("]")
        if closing < 0 or closing + 1 >= len(value) or value[closing + 1] != ":":
            raise ValueError(f"{variable} IPv6 endpoints must use [address]:port")
        host = value[1:closing]
        port_text = value[closing + 2 :]
    else:
        if value.count(":") != 1:
            raise ValueError(f"{variable} entries must use host:port")
        host, port_text = value.rsplit(":", 1)

    host = _validate_upstream_host(host.strip(), variable)
    try:
        port = int(port_text)
    except ValueError as error:
        raise ValueError(f"{variable} ports must be integers") from error
    if port < 1 or port > 65535:
        raise ValueError(f"{variable} ports must be between 1 and 65535")
    return host, port


def _endpoint_list(environment: Mapping[str, str], variable: str) -> list[tuple[str, int]]:
    raw = environment.get(variable, "").strip()
    if not raw:
        return []
    entries = raw.split(",")
    if len(entries) > MAX_PUBLIC_UPSTREAMS:
        raise ValueError(f"{variable} supports at most {MAX_PUBLIC_UPSTREAMS} endpoints")
    endpoints = [_upstream_endpoint(entry, variable) for entry in entries]
    if len(set(endpoints)) != len(endpoints):
        raise ValueError(f"{variable} cannot contain duplicate endpoints")
    return endpoints


def _format_endpoint(endpoint: tuple[str, int]) -> str:
    host, port = endpoint
    return f"[{host}]:{port}" if ":" in host else f"{host}:{port}"


def _upstream_configuration(
    environment: Mapping[str, str], discovery: str
) -> tuple[str, str, tuple[str, str]]:
    public = _endpoint_list(environment, "RNS_PUBLIC_UPSTREAMS")
    public_bootstrap = set(_endpoint_list(environment, "RNS_PUBLIC_BOOTSTRAP_UPSTREAMS"))
    private = _endpoint_list(environment, "RNS_PRIVATE_UPSTREAMS")
    private_bootstrap = set(_endpoint_list(environment, "RNS_PRIVATE_BOOTSTRAP_UPSTREAMS"))

    if not public_bootstrap.issubset(set(public)):
        raise ValueError("RNS_PUBLIC_BOOTSTRAP_UPSTREAMS must be a subset of RNS_PUBLIC_UPSTREAMS")
    if not private_bootstrap.issubset(set(private)):
        raise ValueError("RNS_PRIVATE_BOOTSTRAP_UPSTREAMS must be a subset of RNS_PRIVATE_UPSTREAMS")
    if (public_bootstrap or private_bootstrap) and discovery != "trusted_auto":
        raise ValueError("bootstrap-only upstreams require RNS_PUBLIC_DISCOVERY=trusted_auto")
    overlap = set(public).intersection(private)
    if overlap:
        endpoint = _format_endpoint(sorted(overlap)[0])
        raise ValueError(f"upstream endpoint {endpoint} cannot be both public and private")

    private_name, private_passphrase, private_ifac = _ifac_configuration(
        environment, "RNS_PRIVATE_UPSTREAM_IFAC", "private upstream"
    )
    if private and not private_name:
        raise ValueError("private upstreams require RNS_PRIVATE_UPSTREAM_IFAC_NAME and PASSPHRASE")

    blocks = []
    for index, endpoint in enumerate(public, start=1):
        host, port = endpoint
        blocks.append(
            f"  [[Public boundary {index}]]\n"
            "    type = BackboneInterface\n"
            "    enabled = Yes\n"
            "    mode = boundary\n"
            f"    target_host = {host}\n"
            f"    target_port = {port}\n"
            "    announces_from_internal = No\n"
            f"    bootstrap_only = {'Yes' if endpoint in public_bootstrap else 'No'}"
        )
    for index, endpoint in enumerate(private, start=1):
        host, port = endpoint
        blocks.append(
            f"  [[Private boundary {index}]]\n"
            "    type = BackboneInterface\n"
            "    enabled = Yes\n"
            "    mode = boundary\n"
            f"    target_host = {host}\n"
            f"    target_port = {port}\n"
            "    announces_from_internal = No\n"
            f"    bootstrap_only = {'Yes' if endpoint in private_bootstrap else 'No'}\n"
            f"{private_ifac}"
        )
    block = (
        "\n\n".join(blocks)
        if blocks
        else "  # No public boundary upstreams configured; no private IFAC boundaries configured"
    )
    return ("internal" if blocks else "gateway"), block, (private_name, private_passphrase)


def _ifac_configuration(
    environment: Mapping[str, str], prefix: str, description: str
) -> tuple[str, str, str]:
    name_variable = f"{prefix}_NAME"
    passphrase_variable = f"{prefix}_PASSPHRASE"
    name = _optional_value(environment, name_variable)
    passphrase = _optional_value(environment, passphrase_variable)
    if bool(name) != bool(passphrase):
        raise ValueError(
            f"{name_variable} and {passphrase_variable} must both be set or both be empty"
        )
    if not name:
        return "", "", f"    # Reticulum IFAC disabled for the {description} interface"
    if passphrase.startswith("CHANGE-ME") or len(passphrase) < 12:
        raise ValueError(
            f"{passphrase_variable} must be a non-placeholder value of at least 12 characters"
        )
    block = (
        "    ifac_size = 128\n"
        f"    network_name = {name}\n"
        f"    passphrase = {passphrase}"
    )
    return name, passphrase, block


def _node_list(environment: Mapping[str, str], mesh_mode: str, gateway_role: str) -> str:
    raw = environment.get("RNS_ALLOWED_NODES", "").strip()
    nodes = [item.strip() for item in raw.split(",") if item.strip()]
    if any(NODE_ID.fullmatch(node) is None for node in nodes):
        raise ValueError("RNS_ALLOWED_NODES must be comma-separated !aabbcc11 Node IDs")
    if mesh_mode == "gateway_unicast" and gateway_role == "hub" and not nodes:
        raise ValueError("RNS_ALLOWED_NODES is required for a gateway_unicast hub")
    return "allowed_nodes = " + ", ".join(nodes) if nodes else "# allowed_nodes is intentionally empty"


def _gateway_node(environment: Mapping[str, str], mesh_mode: str, gateway_role: str) -> str:
    raw = environment.get("RNS_GATEWAY_NODE", "").strip()
    if raw and NODE_ID.fullmatch(raw) is None:
        raise ValueError("RNS_GATEWAY_NODE must be a !aabbcc11 Node ID")
    if mesh_mode == "gateway_unicast" and gateway_role == "client" and not raw:
        raise ValueError("RNS_GATEWAY_NODE is required for a gateway_unicast client")
    return f"gateway_node = {raw}" if raw else "# gateway_node is intentionally empty"


def configuration_values(environment: Mapping[str, str]) -> dict[str, str]:
    mesh_mode = _value(environment, "RNS_MESH_MODE", "gateway_unicast")
    if mesh_mode not in {"broadcast", "gateway_unicast", "auto_multi_peer"}:
        raise ValueError("RNS_MESH_MODE must be broadcast, gateway_unicast or auto_multi_peer")
    gateway_role = _value(environment, "RNS_GATEWAY_ROLE", "hub")
    if gateway_role not in {"hub", "client"}:
        raise ValueError("RNS_GATEWAY_ROLE must be hub or client")
    if mesh_mode == "broadcast" and gateway_role != "client":
        raise ValueError("RNS_GATEWAY_ROLE must be client in broadcast mode")
    if mesh_mode == "auto_multi_peer" and gateway_role != "hub":
        raise ValueError("RNS_GATEWAY_ROLE must be hub in auto_multi_peer mode")
    forwarding = _value(environment, "MESHTASTIC_MQTT_FORWARDING_POLICY", "inherit")
    if forwarding not in {"inherit", "force_off"}:
        raise ValueError("MESHTASTIC_MQTT_FORWARDING_POLICY must be inherit or force_off")

    lora_policy = _value(environment, "RNS_LORA_POLICY", "conservative").lower()
    if lora_policy not in {"conservative", "balanced", "custom"}:
        raise ValueError("RNS_LORA_POLICY must be conservative, balanced or custom")
    policy_defaults = {
        "conservative": (2.0, 32, 12, 60.0),
        "balanced": (1.0, 64, 18, 60.0),
        "custom": (2.0, 32, 12, 60.0),
    }
    default_interval, default_queue, default_repairs, default_repair_window = policy_defaults[
        lora_policy
    ]
    tx_interval = _float_value(
        environment, "RNS_RADIO_TX_INTERVAL", default_interval, 0.25, 60.0
    )
    queue_fragments = _integer(
        environment, "RNS_RADIO_QUEUE_FRAGMENTS", default_queue, 4, 1024
    )
    repair_budget = _integer(
        environment, "RNS_REPAIR_REQUEST_BUDGET", default_repairs, 1, 120
    )
    repair_window = _float_value(
        environment, "RNS_REPAIR_BUDGET_WINDOW", default_repair_window, 10.0, 3600.0
    )

    discovery = _value(environment, "RNS_PUBLIC_DISCOVERY", "off").lower()
    if discovery not in {"off", "manual", "trusted_auto"}:
        raise ValueError("RNS_PUBLIC_DISCOVERY must be off, manual or trusted_auto")
    discovery_sources = [
        item.strip().lower()
        for item in environment.get("RNS_DISCOVERY_SOURCES", "").split(",")
        if item.strip()
    ]
    if any(IDENTITY_HASH.fullmatch(source) is None for source in discovery_sources):
        raise ValueError("RNS_DISCOVERY_SOURCES must contain 32-character identity hashes")
    if discovery == "trusted_auto" and not discovery_sources:
        raise ValueError("trusted_auto discovery requires RNS_DISCOVERY_SOURCES")
    discovery_max = _integer(environment, "RNS_DISCOVERY_MAX", 1, 1, 8)
    discovery_required_value = _integer(
        environment, "RNS_DISCOVERY_REQUIRED_VALUE", 14, 1, 255
    )
    discovery_gravity = _integer(environment, "RNS_DISCOVERY_GRAVITY", 0, -100, 100)
    respond_to_probes = _yes_no(environment, "RNS_RESPOND_TO_PROBES", "no")
    autoconnect_policy = (
        "  # autoconnect policy inactive"
        if discovery != "trusted_auto"
        else (
            f"  autoconnect_discovered_interfaces = {discovery_max}\n"
            "  autoconnect_interface_mode = boundary\n"
            f"  autoconnect_interface_gravity = {discovery_gravity}\n"
            "  autoconnect_announces_to_internal = No"
        )
    )
    discovery_sources_line = (
        f"  interface_discovery_sources = {', '.join(discovery_sources)}"
        if discovery_sources
        else "  # interface_discovery_sources omitted: no identity allowlist configured"
    )
    discovery_block = (
        f"  discover_interfaces = {'No' if discovery == 'off' else 'Yes'}\n"
        f"{discovery_sources_line}\n"
        f"  required_discovery_value = {discovery_required_value}\n"
        f"  respond_to_probes = {respond_to_probes.title()}\n"
        f"{autoconnect_policy}"
    )

    private_interface_mode, boundary_upstream_block, private_upstream_ifac = (
        _upstream_configuration(environment, discovery)
    )
    if discovery != "off":
        # Discovery may introduce a public boundary after startup. Keep the
        # radio private before that happens instead of opening a transient
        # public-to-LoRa announce path.
        private_interface_mode = "internal"
    lan_public_visibility = _yes_no(environment, "RNS_LAN_PUBLIC_VISIBILITY", "no")
    lan_interface_mode = (
        "gateway" if lan_public_visibility == "yes" else private_interface_mode
    )

    radio_name, radio_passphrase, radio_ifac = _ifac_configuration(
        environment, "RNS_RADIO_IFAC", "radio"
    )
    tcp_name, tcp_passphrase, tcp_ifac = _ifac_configuration(
        environment, "RNS_TCP_IFAC", "TCP"
    )
    configured_ifacs = [
        ("radio", radio_name, radio_passphrase),
        ("TCP client", tcp_name, tcp_passphrase),
        ("private upstream", *private_upstream_ifac),
    ]
    configured_ifacs = [entry for entry in configured_ifacs if entry[1]]
    for index, left in enumerate(configured_ifacs):
        for right in configured_ifacs[index + 1 :]:
            if left[1:] == right[1:]:
                raise ValueError(f"{left[0]} and {right[0]} IFAC credentials must be different")

    auth_required = _yes_no(environment, "LXMD_AUTH_REQUIRED", "no")
    identities = [
        item.strip().lower()
        for item in environment.get("LXMD_ALLOWED_IDENTITIES", "").split(",")
        if item.strip()
    ]
    if any(IDENTITY_HASH.fullmatch(identity) is None for identity in identities):
        raise ValueError("LXMD_ALLOWED_IDENTITIES must contain 32-character identity hashes")
    if auth_required == "yes" and not identities:
        raise ValueError("LXMD_ALLOWED_IDENTITIES is required when LXMD_AUTH_REQUIRED=yes")

    values = {
        "MESHTASTIC_TCP_HOST": _value(environment, "MESHTASTIC_TCP_HOST"),
        "MESHTASTIC_TCP_PORT": str(
            _integer(environment, "MESHTASTIC_TCP_PORT", 4403, 1, 65535)
        ),
        "MESHTASTIC_CHANNEL_INDEX": str(
            _integer(environment, "MESHTASTIC_CHANNEL_INDEX", 0, 0, 7)
        ),
        "MESHTASTIC_HOP_LIMIT": str(
            _integer(environment, "MESHTASTIC_HOP_LIMIT", 3, 0, 7)
        ),
        "MESHTASTIC_MQTT_FORWARDING_POLICY": forwarding,
        "RNS_LORA_POLICY": lora_policy,
        "RNS_RADIO_TX_INTERVAL": f"{tx_interval:g}",
        "RNS_RADIO_QUEUE_FRAGMENTS": str(queue_fragments),
        "RNS_REPAIR_REQUEST_BUDGET": str(repair_budget),
        "RNS_REPAIR_BUDGET_WINDOW": f"{repair_window:g}",
        "RNS_DISCOVERY_BLOCK": discovery_block,
        "RNS_MESH_MODE": mesh_mode,
        "RNS_GATEWAY_ROLE": gateway_role,
        "RNS_GATEWAY_NODE_LINE": _gateway_node(environment, mesh_mode, gateway_role),
        "RNS_ALLOWED_NODES_LINE": _node_list(environment, mesh_mode, gateway_role),
        "RNS_ACCEPT_BROADCAST_ON_HUB": "Yes" if mesh_mode == "auto_multi_peer" else "No",
        "RNS_MAX_PEERS": str(_integer(environment, "RNS_MAX_PEERS", 32, 1, 512)),
        "RNS_RADIO_INTERFACE_MODE": private_interface_mode,
        "RNS_LAN_INTERFACE_MODE": lan_interface_mode,
        "RNS_BOUNDARY_UPSTREAM_BLOCK": boundary_upstream_block,
        "RNS_RADIO_IFAC_BLOCK": radio_ifac,
        "RNS_TCP_IFAC_BLOCK": tcp_ifac,
        "RNS_TCP_LISTEN_PORT": str(
            _integer(environment, "RNS_TCP_LISTEN_PORT", 4242, 1, 65535)
        ),
        "RNS_LOGLEVEL": str(_integer(environment, "RNS_LOGLEVEL", 4, 0, 7)),
        "LXMD_NODE_NAME": _value(environment, "LXMD_NODE_NAME", "RNS Meshtastic Propagation"),
        "LXMD_ANNOUNCE_INTERVAL": str(
            _integer(environment, "LXMD_ANNOUNCE_INTERVAL", 360, 60, 10_080)
        ),
        "LXMD_AUTOPEER": _yes_no(environment, "LXMD_AUTOPEER", "no"),
        "LXMD_FROM_STATIC_ONLY": _yes_no(environment, "LXMD_FROM_STATIC_ONLY", "yes"),
        "LXMD_STORAGE_LIMIT_MB": str(
            _integer(environment, "LXMD_STORAGE_LIMIT_MB", 64, 8, 4096)
        ),
        "LXMD_MESSAGE_MAX_KB": str(
            _integer(environment, "LXMD_MESSAGE_MAX_KB", 8, 1, 256)
        ),
        "LXMD_SYNC_MAX_KB": str(
            _integer(environment, "LXMD_SYNC_MAX_KB", 64, 8, 10_240)
        ),
        "LXMD_AUTH_REQUIRED": auth_required,
        "LXMD_DISPLAY_NAME": _value(environment, "LXMD_DISPLAY_NAME", "RNS Meshtastic Service"),
        "LXMD_LOGLEVEL": str(_integer(environment, "LXMD_LOGLEVEL", 4, 0, 7)),
        "LXMD_ALLOWED_IDENTITIES": "\n".join(identities) + ("\n" if identities else ""),
    }
    return values


def _atomic_write(path: Path, value: str, mode: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", dir=path.parent, delete=False, encoding="utf-8") as handle:
        handle.write(value)
        temporary = Path(handle.name)
    temporary.chmod(mode)
    temporary.replace(path)


def render_service_profile(
    template_directory: Path,
    rns_directory: Path,
    lxmd_directory: Path,
    environment: Mapping[str, str],
) -> None:
    values = configuration_values(environment)
    rns_template = Template((template_directory / "rns.conf.template").read_text(encoding="utf-8"))
    lxmd_template = Template((template_directory / "lxmd.conf.template").read_text(encoding="utf-8"))
    interface_stub = (template_directory / "RNSMeshtasticInterface.py").read_text(encoding="utf-8")

    _atomic_write(rns_directory / "config", rns_template.substitute(values), 0o600)
    _atomic_write(rns_directory / "interfaces" / "RNSMeshtasticInterface.py", interface_stub, 0o644)
    _atomic_write(lxmd_directory / "config", lxmd_template.substitute(values), 0o600)
    allowed = values["LXMD_ALLOWED_IDENTITIES"]
    if allowed:
        _atomic_write(lxmd_directory / "allowed", allowed, 0o600)
    elif (lxmd_directory / "allowed").exists():
        (lxmd_directory / "allowed").unlink()


def main() -> None:
    parser = argparse.ArgumentParser(description="Render the managed Linux rnsd/lxmd service profile")
    parser.add_argument("--templates", type=Path, required=True)
    parser.add_argument("--rns-dir", type=Path, required=True)
    parser.add_argument("--lxmd-dir", type=Path, required=True)
    args = parser.parse_args()
    render_service_profile(args.templates, args.rns_dir, args.lxmd_dir, os.environ)


if __name__ == "__main__":
    main()
