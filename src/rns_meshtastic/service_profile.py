from __future__ import annotations

import argparse
import os
import re
import tempfile
from collections.abc import Mapping
from pathlib import Path
from string import Template

NODE_ID = re.compile(r"![0-9a-fA-F]{8}")
IDENTITY_HASH = re.compile(r"[0-9a-fA-F]{32}")


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


def _yes_no(environment: Mapping[str, str], name: str, default: str) -> str:
    value = _value(environment, name, default).lower()
    if value not in {"yes", "no"}:
        raise ValueError(f"{name} must be yes or no")
    return value


def _secret(environment: Mapping[str, str], name: str) -> str:
    value = _value(environment, name)
    if value.startswith("CHANGE-ME") or len(value) < 12:
        raise ValueError(f"{name} must be a non-placeholder value of at least 12 characters")
    return value


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
    if mesh_mode not in {"broadcast", "gateway_unicast"}:
        raise ValueError("RNS_MESH_MODE must be broadcast or gateway_unicast")
    gateway_role = _value(environment, "RNS_GATEWAY_ROLE", "hub")
    if gateway_role not in {"hub", "client"}:
        raise ValueError("RNS_GATEWAY_ROLE must be hub or client")
    if mesh_mode == "broadcast" and gateway_role != "client":
        raise ValueError("RNS_GATEWAY_ROLE must be client in broadcast mode")
    forwarding = _value(environment, "MESHTASTIC_MQTT_FORWARDING_POLICY", "inherit")
    if forwarding not in {"inherit", "force_off"}:
        raise ValueError("MESHTASTIC_MQTT_FORWARDING_POLICY must be inherit or force_off")

    radio_name = _value(environment, "RNS_RADIO_IFAC_NAME")
    radio_passphrase = _secret(environment, "RNS_RADIO_IFAC_PASSPHRASE")
    tcp_name = _value(environment, "RNS_TCP_IFAC_NAME")
    tcp_passphrase = _secret(environment, "RNS_TCP_IFAC_PASSPHRASE")
    if (radio_name, radio_passphrase) == (tcp_name, tcp_passphrase):
        raise ValueError("radio and TCP client IFAC credentials must be different")

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
        "RNS_MESH_MODE": mesh_mode,
        "RNS_GATEWAY_ROLE": gateway_role,
        "RNS_GATEWAY_NODE_LINE": _gateway_node(environment, mesh_mode, gateway_role),
        "RNS_ALLOWED_NODES_LINE": _node_list(environment, mesh_mode, gateway_role),
        "RNS_RADIO_IFAC_NAME": radio_name,
        "RNS_RADIO_IFAC_PASSPHRASE": radio_passphrase,
        "RNS_TCP_IFAC_NAME": tcp_name,
        "RNS_TCP_IFAC_PASSPHRASE": tcp_passphrase,
        "RNS_TCP_LISTEN_PORT": str(
            _integer(environment, "RNS_TCP_LISTEN_PORT", 4242, 1, 65535)
        ),
        "RNS_LOGLEVEL": str(_integer(environment, "RNS_LOGLEVEL", 4, 0, 7)),
        "LXMD_NODE_NAME": _value(environment, "LXMD_NODE_NAME", "RNS Meshtastic Propagation"),
        "LXMD_ANNOUNCE_INTERVAL": str(
            _integer(environment, "LXMD_ANNOUNCE_INTERVAL", 360, 60, 10_080)
        ),
        "LXMD_AUTOPEER": _yes_no(environment, "LXMD_AUTOPEER", "no"),
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
