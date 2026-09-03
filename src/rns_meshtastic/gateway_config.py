"""Safe Linux gateway configuration validation, staging and apply helpers."""

from __future__ import annotations

import ipaddress
import os
import re
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path

from rns_meshtastic.service_profile import configuration_values

SECRET_SUFFIXES = ("PASSWORD", "PASSPHRASE", "SECRET", "TOKEN", "KEY")
POLICY_PROFILES = {
    "conservative": {
        "RNS_RADIO_TX_INTERVAL": "2.0",
        "RNS_RADIO_QUEUE_FRAGMENTS": "32",
        "RNS_REPAIR_REQUEST_BUDGET": "12",
        "RNS_REPAIR_BUDGET_WINDOW": "60",
    },
    "balanced": {
        "RNS_RADIO_TX_INTERVAL": "1.0",
        "RNS_RADIO_QUEUE_FRAGMENTS": "64",
        "RNS_REPAIR_REQUEST_BUDGET": "18",
        "RNS_REPAIR_BUDGET_WINDOW": "60",
    },
}
DISCOVERY_MODES = {"off", "manual", "trusted_auto"}
SAFE_NAME = re.compile(r"[A-Z][A-Z0-9_]*\Z")
MANAGED_FIELDS = (
    "MESHTASTIC_TCP_HOST",
    "MESHTASTIC_TCP_PORT",
    "MESHTASTIC_CHANNEL_INDEX",
    "MESHTASTIC_HOP_LIMIT",
    "MESHTASTIC_MQTT_FORWARDING_POLICY",
    "RNS_MESH_MODE",
    "RNS_GATEWAY_ROLE",
    "RNS_GATEWAY_NODE",
    "RNS_ALLOWED_NODES",
    "RNS_MAX_PEERS",
    "RNS_PEER_ANNOUNCE_IDLE_SECONDS",
    "RNS_LORA_POLICY",
    "RNS_RADIO_TX_INTERVAL",
    "RNS_RADIO_QUEUE_FRAGMENTS",
    "RNS_REPAIR_REQUEST_BUDGET",
    "RNS_REPAIR_BUDGET_WINDOW",
    "RNS_PUBLIC_UPSTREAMS",
    "RNS_PUBLIC_BOOTSTRAP_UPSTREAMS",
    "RNS_PRIVATE_UPSTREAMS",
    "RNS_PRIVATE_BOOTSTRAP_UPSTREAMS",
    "RNS_PRIVATE_UPSTREAM_IFAC_NAME",
    "RNS_PRIVATE_UPSTREAM_IFAC_PASSPHRASE",
    "RNS_LAN_PUBLIC_VISIBILITY",
    "RNS_PUBLIC_DISCOVERY",
    "RNS_DISCOVERY_SOURCES",
    "RNS_DISCOVERY_MAX",
    "RNS_DISCOVERY_REQUIRED_VALUE",
    "RNS_DISCOVERY_GRAVITY",
    "RNS_RESPOND_TO_PROBES",
    "RNS_RADIO_IFAC_NAME",
    "RNS_RADIO_IFAC_PASSPHRASE",
    "RNS_TCP_IFAC_NAME",
    "RNS_TCP_IFAC_PASSPHRASE",
    "RNS_TCP_PUBLISH_IP",
    "RNS_TCP_LISTEN_PORT",
    "RNS_LAN_ALLOWLIST",
    "RNS_LAN_DENYLIST",
    "RNS_LOGLEVEL",
    "RNS_CONSOLE_PUBLISH_IP",
    "RNS_CONSOLE_PORT",
    "RNS_CONSOLE_AUTH_MODE",
    "RNS_CONSOLE_USERNAME",
    "RNS_CONSOLE_PASSWORD",
    "LXMD_NODE_NAME",
    "LXMD_DISPLAY_NAME",
    "LXMD_ANNOUNCE_INTERVAL",
    "LXMD_MANUAL_ANNOUNCE_COOLDOWN_SECONDS",
    "LXMD_AUTOPEER",
    "LXMD_FROM_STATIC_ONLY",
    "LXMD_STORAGE_LIMIT_MB",
    "LXMD_MESSAGE_MAX_KB",
    "LXMD_SYNC_MAX_KB",
    "LXMD_AUTH_REQUIRED",
    "LXMD_ALLOWED_IDENTITIES",
    "LXMD_LOGLEVEL",
)


@dataclass(frozen=True, slots=True)
class ValidationResult:
    values: dict[str, str]
    warnings: tuple[str, ...]


def is_secret(name: str) -> bool:
    upper = name.upper()
    return any(upper.endswith(suffix) for suffix in SECRET_SUFFIXES)


def redact_environment(environment: Mapping[str, str]) -> dict[str, str]:
    return {
        name: "<configured>" if is_secret(name) and value else value for name, value in environment.items()
    }


def parse_env_file(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[7:].lstrip()
        if "=" not in line:
            raise ValueError(f"{path}:{number}: expected NAME=value")
        name, value = line.split("=", 1)
        name = name.strip()
        if SAFE_NAME.fullmatch(name) is None:
            raise ValueError(f"{path}:{number}: invalid variable name {name!r}")
        if "\x00" in value or "\r" in value or "\n" in value:
            raise ValueError(f"{path}:{number}: value must be a single line")
        values[name] = value.strip()
    return values


def render_env(environment: Mapping[str, str]) -> str:
    lines = ["# Managed by RNS Meshtastic Gateway Console; keep mode 0600."]
    for name in sorted(environment):
        value = str(environment[name])
        if any(character in value for character in "\r\n\x00"):
            raise ValueError(f"{name} must be a single line")
        lines.append(f"{name}={value}")
    return "\n".join(lines) + "\n"


def validate_gateway_environment(environment: Mapping[str, str]) -> ValidationResult:
    values = {str(name): str(value).strip() for name, value in environment.items()}
    profile = values.get("RNS_LORA_POLICY", "conservative").lower()
    if profile not in {"conservative", "balanced", "custom"}:
        raise ValueError("RNS_LORA_POLICY must be conservative, balanced or custom")
    values["RNS_LORA_POLICY"] = profile
    profile_overrides: list[str] = []
    if profile != "custom":
        for name, default in POLICY_PROFILES[profile].items():
            if name in values and values[name] != default:
                profile_overrides.append(name)
            # Named profiles are policies, not suggestions. Always render their
            # bounded values so stale expert settings cannot silently weaken a
            # profile selected in Console.
            values[name] = default

    discovery = values.get("RNS_PUBLIC_DISCOVERY", "off").lower()
    if discovery not in DISCOVERY_MODES:
        raise ValueError("RNS_PUBLIC_DISCOVERY must be off, manual or trusted_auto")
    sources = [
        item.strip().lower() for item in values.get("RNS_DISCOVERY_SOURCES", "").split(",") if item.strip()
    ]
    if any(re.fullmatch(r"[0-9a-f]{32}", item) is None for item in sources):
        raise ValueError("RNS_DISCOVERY_SOURCES must contain 32-character identity hashes")
    if discovery == "trusted_auto" and not sources:
        raise ValueError("trusted_auto discovery requires RNS_DISCOVERY_SOURCES")
    values["RNS_PUBLIC_DISCOVERY"] = discovery
    values["RNS_DISCOVERY_SOURCES"] = ",".join(sources)

    for name in ("RNS_LAN_ALLOWLIST", "RNS_LAN_DENYLIST"):
        raw = [item.strip() for item in values.get(name, "").split(",") if item.strip()]
        try:
            networks = [str(ipaddress.ip_network(item, strict=False)) for item in raw]
        except ValueError as error:
            raise ValueError(f"{name} must contain IPv4/IPv6 addresses or CIDR networks") from error
        if len(set(networks)) != len(networks):
            raise ValueError(f"{name} cannot contain duplicate networks")
        values[name] = ",".join(networks)

    console_mode = values.get("RNS_CONSOLE_AUTH_MODE", "off").lower()
    if console_mode not in {"off", "basic"}:
        raise ValueError("RNS_CONSOLE_AUTH_MODE must be off or basic")
    values["RNS_CONSOLE_AUTH_MODE"] = console_mode
    publish_ip = values.get("RNS_CONSOLE_PUBLISH_IP", "127.0.0.1") or "127.0.0.1"
    try:
        address = ipaddress.ip_address(publish_ip)
    except ValueError as error:
        raise ValueError("RNS_CONSOLE_PUBLISH_IP must be an IPv4 or IPv6 address") from error
    username = values.get("RNS_CONSOLE_USERNAME", "")
    password = values.get("RNS_CONSOLE_PASSWORD", "")
    if console_mode == "off":
        if not address.is_loopback:
            raise ValueError(
                "RNS_CONSOLE_AUTH_MODE=basic is required when Console is published outside loopback"
            )
        if username or password:
            raise ValueError("Console credentials must be empty when RNS_CONSOLE_AUTH_MODE=off")
    else:
        if re.fullmatch(r"[A-Za-z0-9._@-]{1,64}", username) is None:
            raise ValueError("RNS_CONSOLE_USERNAME must use 1-64 safe username characters")
        if password.startswith("CHANGE-ME") or len(password) < 16:
            raise ValueError("RNS_CONSOLE_PASSWORD must be a non-placeholder value of at least 16 characters")
        if password == username:
            raise ValueError("RNS_CONSOLE_PASSWORD must differ from RNS_CONSOLE_USERNAME")

    try:
        manual_announce_cooldown = int(values.get("LXMD_MANUAL_ANNOUNCE_COOLDOWN_SECONDS", "900"))
    except ValueError as error:
        raise ValueError("LXMD_MANUAL_ANNOUNCE_COOLDOWN_SECONDS must be an integer") from error
    if not 300 <= manual_announce_cooldown <= 86400:
        raise ValueError("LXMD_MANUAL_ANNOUNCE_COOLDOWN_SECONDS must be between 300 and 86400")
    values["LXMD_MANUAL_ANNOUNCE_COOLDOWN_SECONDS"] = str(manual_announce_cooldown)

    try:
        peer_announce_idle = int(values.get("RNS_PEER_ANNOUNCE_IDLE_SECONDS", "900"))
    except ValueError as error:
        raise ValueError("RNS_PEER_ANNOUNCE_IDLE_SECONDS must be an integer") from error
    if peer_announce_idle != 0 and not 300 <= peer_announce_idle <= 86400:
        raise ValueError("RNS_PEER_ANNOUNCE_IDLE_SECONDS must be 0 or between 300 and 86400")
    values["RNS_PEER_ANNOUNCE_IDLE_SECONDS"] = str(peer_announce_idle)

    if values.get("MESHTASTIC_OVERRIDE_DUTY_CYCLE", "no").lower() not in {"", "no", "false", "0"}:
        raise ValueError("Meshtastic duty-cycle override is forbidden by the gateway safety policy")

    # Reuse the renderer's complete structural validation. It also guarantees
    # that public upstreams render as boundary and never as the radio mode.
    configuration_values(values)

    warnings: list[str] = []
    if profile_overrides:
        warnings.append(
            f"{profile} LoRa policy replaced expert overrides for: " + ", ".join(profile_overrides)
        )
    if profile == "custom":
        warnings.append("custom LoRa policy bypasses conservative queue and pacing defaults")
    if discovery == "manual" and not sources:
        warnings.append("manual discovery accepts candidates from every connected Reticulum network")
    if values.get("RNS_LAN_PUBLIC_VISIBILITY", "no").lower() == "yes":
        warnings.append("boundary announces are visible to trusted LAN/VPN clients")
    if values.get("RNS_PRIVATE_UPSTREAMS", ""):
        warnings.append("private upstreams are boundary interfaces and share one dedicated IFAC profile")
    if console_mode == "basic":
        warnings.append("Console Basic authentication requires a trusted VPN or HTTPS for confidentiality")
    if values.get("MESHTASTIC_MQTT_FORWARDING_POLICY", "inherit") == "inherit":
        warnings.append("Meshtastic may forward bridge packets through MQTT when the radio permits it")
    return ValidationResult(values=values, warnings=tuple(warnings))


def stage_environment(directory: Path, environment: Mapping[str, str]) -> Path:
    validated = validate_gateway_environment(environment)
    directory.mkdir(parents=True, exist_ok=True)
    stage_id = datetime.now(UTC).strftime("%Y%m%dT%H%M%S.%fZ")
    target = directory / f"gateway-{stage_id}.env"
    with tempfile.NamedTemporaryFile("w", dir=directory, delete=False, encoding="utf-8") as handle:
        handle.write(render_env(validated.values))
        temporary = Path(handle.name)
    temporary.chmod(0o600)
    temporary.replace(target)
    return target


def apply_staged_environment(
    staged: Path,
    target: Path,
    *,
    compose_file: Path,
    health_url: str,
    timeout: float = 120.0,
) -> Path:
    """Apply an explicit stage, restart Compose and roll back on failed health."""

    validated = validate_gateway_environment(parse_env_file(staged))
    target.parent.mkdir(parents=True, exist_ok=True)
    backup = target.with_name(target.name + ".backup-" + datetime.now(UTC).strftime("%Y%m%dT%H%M%SZ"))
    if target.exists():
        shutil.copy2(target, backup)
    else:
        backup.write_text("", encoding="utf-8")
        backup.chmod(0o600)

    def write_target(values: Mapping[str, str]) -> None:
        with tempfile.NamedTemporaryFile("w", dir=target.parent, delete=False, encoding="utf-8") as handle:
            handle.write(render_env(values))
            temporary = Path(handle.name)
        temporary.chmod(0o600)
        temporary.replace(target)

    def compose_up() -> None:
        result = subprocess.run(
            [
                "docker",
                "compose",
                "--env-file",
                str(target),
                "-f",
                str(compose_file),
                "up",
                "-d",
                "--build",
                "rnsd",
                "lxmd",
                "gateway-console",
            ],
            check=False,
            text=True,
        )
        if result.returncode != 0:
            raise RuntimeError(f"docker compose failed with exit code {result.returncode}")

    try:
        write_target(validated.values)
        compose_up()
        deadline = time.monotonic() + timeout
        last_error = "health endpoint did not respond"
        while time.monotonic() < deadline:
            try:
                with urllib.request.urlopen(health_url, timeout=5) as response:
                    if response.status == 200:
                        return backup
                    last_error = f"health endpoint returned HTTP {response.status}"
            except (OSError, urllib.error.URLError) as error:
                last_error = str(error)
            time.sleep(2)
        raise RuntimeError(last_error)
    except Exception:
        if backup.stat().st_size:
            shutil.copy2(backup, target)
            target.chmod(0o600)
            compose_up()
        else:
            target.unlink(missing_ok=True)
        raise


def environment_from_process() -> dict[str, str]:
    # Never absorb unrelated process environment (CI tokens, proxy passwords,
    # HOME/PATH, etc.) into a staged gateway file.
    return {name: os.environ[name] for name in MANAGED_FIELDS if name in os.environ}
