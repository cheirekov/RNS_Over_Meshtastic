from pathlib import Path

import pytest

from rns_meshtastic.gateway_config import (
    MANAGED_FIELDS,
    apply_staged_environment,
    environment_from_process,
    parse_env_file,
    redact_environment,
    render_env,
    stage_environment,
    validate_gateway_environment,
)


def test_example_environment_documents_every_managed_setting() -> None:
    example = Path(__file__).parents[1] / "examples" / "linux-service.env.example"
    assert tuple(parse_env_file(example)) == MANAGED_FIELDS


def environment() -> dict[str, str]:
    return {
        "MESHTASTIC_TCP_HOST": "192.0.2.10",
        "MESHTASTIC_HOP_LIMIT": "3",
        "RNS_ALLOWED_NODES": "!a1b3b3b8",
        "RNS_RADIO_IFAC_NAME": "private-radio",
        "RNS_RADIO_IFAC_PASSPHRASE": "not-shown-secret",
        "RNS_TCP_IFAC_NAME": "private-lan",
        "RNS_TCP_IFAC_PASSPHRASE": "another-secret",
    }


def test_conservative_profile_applies_bounded_defaults():
    result = validate_gateway_environment(environment())
    assert result.values["RNS_LORA_POLICY"] == "conservative"
    assert result.values["RNS_RADIO_TX_INTERVAL"] == "2.0"
    assert result.values["RNS_RADIO_QUEUE_FRAGMENTS"] == "32"
    assert result.values["RNS_PEER_ANNOUNCE_IDLE_SECONDS"] == "900"
    assert result.values["RNS_REPAIR_REQUEST_BUDGET"] == "12"
    assert result.values["LXMD_MANUAL_ANNOUNCE_COOLDOWN_SECONDS"] == "900"


def test_named_policy_replaces_stale_expert_overrides():
    result = validate_gateway_environment(
        environment()
        | {
            "RNS_LORA_POLICY": "conservative",
            "RNS_RADIO_TX_INTERVAL": "0.25",
            "RNS_RADIO_QUEUE_FRAGMENTS": "1024",
        }
    )
    assert result.values["RNS_RADIO_TX_INTERVAL"] == "2.0"
    assert result.values["RNS_RADIO_QUEUE_FRAGMENTS"] == "32"
    assert any("replaced expert overrides" in warning for warning in result.warnings)


def test_custom_policy_preserves_valid_expert_values():
    result = validate_gateway_environment(
        environment()
        | {
            "RNS_LORA_POLICY": "custom",
            "RNS_RADIO_TX_INTERVAL": "3.0",
            "RNS_RADIO_QUEUE_FRAGMENTS": "48",
            "RNS_REPAIR_REQUEST_BUDGET": "8",
            "RNS_REPAIR_BUDGET_WINDOW": "90",
        }
    )
    assert result.values["RNS_RADIO_TX_INTERVAL"] == "3.0"
    assert result.values["RNS_RADIO_QUEUE_FRAGMENTS"] == "48"


def test_manual_announce_cooldown_has_a_hard_minimum():
    with pytest.raises(ValueError, match="between 300 and 86400"):
        validate_gateway_environment(environment() | {"LXMD_MANUAL_ANNOUNCE_COOLDOWN_SECONDS": "299"})


def test_peer_announce_idle_safeguard_can_be_disabled_but_not_misconfigured():
    assert (
        validate_gateway_environment(environment() | {"RNS_PEER_ANNOUNCE_IDLE_SECONDS": "0"}).values[
            "RNS_PEER_ANNOUNCE_IDLE_SECONDS"
        ]
        == "0"
    )
    with pytest.raises(ValueError, match="must be 0 or between 300 and 86400"):
        validate_gateway_environment(environment() | {"RNS_PEER_ANNOUNCE_IDLE_SECONDS": "299"})


def test_trusted_discovery_requires_allowlisted_identity():
    with pytest.raises(ValueError, match="requires RNS_DISCOVERY_SOURCES"):
        validate_gateway_environment(environment() | {"RNS_PUBLIC_DISCOVERY": "trusted_auto"})

    result = validate_gateway_environment(
        environment()
        | {
            "RNS_PUBLIC_DISCOVERY": "trusted_auto",
            "RNS_DISCOVERY_SOURCES": "0123456789ABCDEF0123456789ABCDEF",
        }
    )
    assert result.values["RNS_DISCOVERY_SOURCES"] == "0123456789abcdef0123456789abcdef"


def test_duty_cycle_override_is_always_rejected():
    with pytest.raises(ValueError, match="duty-cycle override"):
        validate_gateway_environment(environment() | {"MESHTASTIC_OVERRIDE_DUTY_CYCLE": "yes"})


def test_console_authentication_is_mandatory_outside_loopback():
    with pytest.raises(ValueError, match="required when Console is published outside loopback"):
        validate_gateway_environment(
            environment()
            | {
                "RNS_CONSOLE_PUBLISH_IP": "172.16.19.10",
                "RNS_CONSOLE_AUTH_MODE": "off",
            }
        )

    result = validate_gateway_environment(
        environment()
        | {
            "RNS_CONSOLE_PUBLISH_IP": "172.16.19.10",
            "RNS_CONSOLE_AUTH_MODE": "basic",
            "RNS_CONSOLE_USERNAME": "operator",
            "RNS_CONSOLE_PASSWORD": "correct-horse-battery-staple",
        }
    )
    assert result.values["RNS_CONSOLE_AUTH_MODE"] == "basic"


def test_console_basic_auth_rejects_short_password():
    with pytest.raises(ValueError, match="at least 16"):
        validate_gateway_environment(
            environment()
            | {
                "RNS_CONSOLE_AUTH_MODE": "basic",
                "RNS_CONSOLE_USERNAME": "operator",
                "RNS_CONSOLE_PASSWORD": "too-short",
            }
        )


def test_secrets_are_redacted_but_staged_file_is_private(tmp_path: Path):
    redacted = redact_environment(environment())
    assert redacted["RNS_RADIO_IFAC_PASSPHRASE"] == "<configured>"
    assert "not-shown-secret" not in render_env(redacted)

    stage = stage_environment(tmp_path, environment())
    assert stage.stat().st_mode & 0o777 == 0o600
    parsed = parse_env_file(stage)
    assert parsed["RNS_RADIO_IFAC_PASSPHRASE"] == "not-shown-secret"


def test_env_parser_rejects_shell_syntax(tmp_path: Path):
    target = tmp_path / "bad.env"
    target.write_text("GOOD=value\nnot-valid=value\n", encoding="utf-8")
    with pytest.raises(ValueError, match="invalid variable"):
        parse_env_file(target)


def test_process_environment_is_strictly_allowlisted(monkeypatch):
    monkeypatch.setenv("MESHTASTIC_TCP_HOST", "192.0.2.10")
    monkeypatch.setenv("UNRELATED_CI_TOKEN", "must-not-be-staged")
    values = environment_from_process()
    assert values["MESHTASTIC_TCP_HOST"] == "192.0.2.10"
    assert "UNRELATED_CI_TOKEN" not in values


def test_example_documents_every_managed_setting():
    example = Path(__file__).parents[1] / "examples" / "linux-service.env.example"
    values = parse_env_file(example)
    assert set(values) == set(MANAGED_FIELDS)
    validate_gateway_environment(values)


class _HealthyResponse:
    status = 200

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False


def test_explicit_apply_creates_backup_and_requires_health(monkeypatch, tmp_path: Path):
    target = tmp_path / "gateway.env"
    target.write_text(render_env(validate_gateway_environment(environment()).values))
    staged = stage_environment(tmp_path / "stages", environment() | {"MESHTASTIC_HOP_LIMIT": "2"})
    commands = []
    monkeypatch.setattr(
        "rns_meshtastic.gateway_config.subprocess.run",
        lambda command, **kwargs: commands.append(command) or type("Result", (), {"returncode": 0})(),
    )
    monkeypatch.setattr(
        "rns_meshtastic.gateway_config.urllib.request.urlopen",
        lambda *args, **kwargs: _HealthyResponse(),
    )

    backup = apply_staged_environment(
        staged,
        target,
        compose_file=tmp_path / "compose.yaml",
        health_url="http://127.0.0.1:8787/healthz",
        timeout=1,
    )
    assert backup.exists()
    assert parse_env_file(target)["MESHTASTIC_HOP_LIMIT"] == "2"
    assert commands and commands[0][-3:] == ["rnsd", "lxmd", "gateway-console"]


def test_failed_health_check_rolls_back_target(monkeypatch, tmp_path: Path):
    original = validate_gateway_environment(environment()).values
    target = tmp_path / "gateway.env"
    target.write_text(render_env(original))
    staged = stage_environment(tmp_path / "stages", environment() | {"MESHTASTIC_HOP_LIMIT": "1"})
    calls = []
    monkeypatch.setattr(
        "rns_meshtastic.gateway_config.subprocess.run",
        lambda command, **kwargs: calls.append(command) or type("Result", (), {"returncode": 0})(),
    )

    with pytest.raises(RuntimeError, match="health endpoint"):
        apply_staged_environment(
            staged,
            target,
            compose_file=tmp_path / "compose.yaml",
            health_url="http://127.0.0.1:8787/healthz",
            timeout=0,
        )
    assert parse_env_file(target)["MESHTASTIC_HOP_LIMIT"] == original.get("MESHTASTIC_HOP_LIMIT", "3")
    assert len(calls) == 2
