from __future__ import annotations

import argparse
import json
from pathlib import Path

from rns_meshtastic.cli import _gateway_validate


def test_gateway_validate_json_is_machine_readable(tmp_path: Path, capsys) -> None:
    environment = tmp_path / "gateway.env"
    environment.write_text(
        "\n".join(
            (
                "MESHTASTIC_TCP_HOST=192.0.2.10",
                "RNS_MESH_MODE=gateway_unicast",
                "RNS_GATEWAY_ROLE=hub",
                "RNS_ALLOWED_NODES=!a1b3b3b8",
                "RNS_RADIO_IFAC_NAME=test-radio",
                "RNS_RADIO_IFAC_PASSPHRASE=not-a-real-secret",
            )
        ),
        encoding="utf-8",
    )

    assert _gateway_validate(argparse.Namespace(env_file=str(environment), json=True)) == 0
    output = json.loads(capsys.readouterr().out)
    assert output["valid"] is True
    assert output["values"]["RNS_RADIO_IFAC_PASSPHRASE"] == "<configured>"


def test_gateway_validate_human_output_is_not_json(tmp_path: Path, capsys) -> None:
    environment = tmp_path / "gateway.env"
    environment.write_text(
        "MESHTASTIC_TCP_HOST=192.0.2.10\n"
        "RNS_MESH_MODE=gateway_unicast\n"
        "RNS_GATEWAY_ROLE=hub\n"
        "RNS_ALLOWED_NODES=!a1b3b3b8\n",
        encoding="utf-8",
    )

    assert _gateway_validate(argparse.Namespace(env_file=str(environment), json=False)) == 0
    output = capsys.readouterr().out
    assert output.startswith("gateway configuration is valid\n")
