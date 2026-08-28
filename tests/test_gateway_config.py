from pathlib import Path

import pytest

from rns_meshtastic.gateway_config import (
    apply_staged_environment,
    environment_from_process,
    parse_env_file,
    redact_environment,
    render_env,
    stage_environment,
    validate_gateway_environment,
)


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
    assert result.values["RNS_REPAIR_REQUEST_BUDGET"] == "12"


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
        validate_gateway_environment(
            environment() | {"MESHTASTIC_OVERRIDE_DUTY_CYCLE": "yes"}
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


class _HealthyResponse:
    status = 200

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False


def test_explicit_apply_creates_backup_and_requires_health(monkeypatch, tmp_path: Path):
    target = tmp_path / "gateway.env"
    target.write_text(render_env(validate_gateway_environment(environment()).values))
    staged = stage_environment(
        tmp_path / "stages", environment() | {"MESHTASTIC_HOP_LIMIT": "2"}
    )
    commands = []
    monkeypatch.setattr(
        "rns_meshtastic.gateway_config.subprocess.run",
        lambda command, **kwargs: commands.append(command)
        or type("Result", (), {"returncode": 0})(),
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
    staged = stage_environment(
        tmp_path / "stages", environment() | {"MESHTASTIC_HOP_LIMIT": "1"}
    )
    calls = []
    monkeypatch.setattr(
        "rns_meshtastic.gateway_config.subprocess.run",
        lambda command, **kwargs: calls.append(command)
        or type("Result", (), {"returncode": 0})(),
    )

    with pytest.raises(RuntimeError, match="health endpoint"):
        apply_staged_environment(
            staged,
            target,
            compose_file=tmp_path / "compose.yaml",
            health_url="http://127.0.0.1:8787/healthz",
            timeout=0,
        )
    assert parse_env_file(target)["MESHTASTIC_HOP_LIMIT"] == original.get(
        "MESHTASTIC_HOP_LIMIT", "3"
    )
    assert len(calls) == 2
