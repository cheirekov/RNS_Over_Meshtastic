import time
from pathlib import Path

import pytest

from rns_meshtastic.gateway_apply_queue import (
    agent_status,
    claim_request,
    complete_request,
    heartbeat,
    queue_request,
)

STAGE_ID = "gateway-20260903T120000.123456Z.env"
AGENT_ID = "0123456789abcdef"


def test_apply_queue_requires_live_agent_and_known_stage(tmp_path: Path):
    stages = tmp_path / "stages"
    stages.mkdir()
    (stages / STAGE_ID).write_text("RNS_LORA_POLICY=conservative\n", encoding="utf-8")
    control = tmp_path / "control"

    with pytest.raises(RuntimeError, match="agent is offline"):
        queue_request(control, stages, STAGE_ID)

    heartbeat(control, AGENT_ID)
    with pytest.raises(ValueError, match="invalid stage_id"):
        queue_request(control, stages, "../.env.linux-service")
    with pytest.raises(ValueError, match="no longer exists"):
        queue_request(control, stages, "gateway-20260903T120001.123456Z.env")


def test_apply_queue_claim_and_completion_lifecycle(tmp_path: Path):
    stages = tmp_path / "stages"
    stages.mkdir()
    (stages / STAGE_ID).write_text("RNS_LORA_POLICY=conservative\n", encoding="utf-8")
    control = tmp_path / "control"
    heartbeat(control, AGENT_ID)

    queued = queue_request(control, stages, STAGE_ID)
    assert queued["state"] == "queued"
    assert agent_status(control)["state"] == "queued"
    with pytest.raises(RuntimeError, match="already queued"):
        queue_request(control, stages, STAGE_ID)

    claimed = claim_request(control, AGENT_ID)
    assert claimed is not None
    assert claimed["stage_id"] == STAGE_ID
    assert claim_request(control, AGENT_ID) is None
    assert agent_status(control)["state"] == "applying"

    result = complete_request(
        control,
        claimed["request_id"],
        succeeded=True,
        message="services healthy",
        backup="/private/path/.env.backup-1",
    )
    assert result["state"] == "succeeded"
    status = agent_status(control)
    assert status["message"] == "services healthy"
    assert status["backup"] == ".env.backup-1"
    assert not (control / "active.json").exists()


def test_apply_agent_status_expires_stale_heartbeat(tmp_path: Path):
    heartbeat(tmp_path, AGENT_ID)
    assert agent_status(tmp_path)["available"] is True
    heartbeat_path = tmp_path / "heartbeat.json"
    value = __import__("json").loads(heartbeat_path.read_text(encoding="utf-8"))
    value["unix_time"] = time.time() - 60
    heartbeat_path.write_text(__import__("json").dumps(value), encoding="utf-8")
    assert agent_status(tmp_path)["available"] is False
