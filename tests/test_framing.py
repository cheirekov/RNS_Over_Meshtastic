import json
from pathlib import Path

import pytest

from rns_meshtastic.framing import HEADER, REQUEST_PREFIX, FragmentError, FragmentProtocol


def test_port76_binary_vector_is_stable_and_round_trips():
    vector = json.loads(
        (Path(__file__).parent / "vectors" / "port76-v1.json").read_text()
    )
    frame = bytes.fromhex(vector["frame_hex"])
    sender = FragmentProtocol(fragment_body=vector["fragment_body"])
    fragments = sender.encode(frame, vector["destination"])

    assert [item.payload.hex() for item in fragments] == vector["fragments_hex"]
    assert (REQUEST_PREFIX + HEADER.pack(0, 2)).hex() == vector["repair_second_hex"]
    assert (REQUEST_PREFIX + HEADER.pack(0, 0)).hex() == vector["repair_final_hex"]

    receiver = FragmentProtocol(fragment_body=vector["fragment_body"])
    completed = []
    for item in fragments:
        completed.extend(receiver.receive(vector["source"], item.payload).frames)
    assert completed == [frame]


def test_round_trip_in_order():
    sender = FragmentProtocol(fragment_body=10)
    receiver = FragmentProtocol(fragment_body=10)
    frame = b"a" * 31
    fragments = sender.encode(frame, "^all")

    frames = []
    for fragment in fragments:
        frames.extend(receiver.receive("!00000001", fragment.payload).frames)

    assert frames == [frame]
    assert [HEADER.unpack_from(item.payload)[1] for item in fragments] == [1, 2, 3, -4]


def test_round_trip_out_of_order():
    sender = FragmentProtocol(fragment_body=8)
    receiver = FragmentProtocol(fragment_body=8)
    frame = bytes(range(50))
    fragments = sender.encode(frame, "!00000002")

    frames = []
    for fragment in reversed(fragments):
        frames.extend(receiver.receive("!00000001", fragment.payload).frames)

    assert frames == [frame]


def test_missing_fragment_is_requested_and_retransmitted():
    sender = FragmentProtocol(fragment_body=10, request_cooldown=0.1)
    receiver = FragmentProtocol(fragment_body=10, request_cooldown=0.1)
    frame = b"0123456789" * 4
    fragments = sender.encode(frame, "^all")

    receiver.receive("!00000001", fragments[0].payload)
    final_result = receiver.receive("!00000001", fragments[-1].payload)
    assert len(final_result.transmissions) == 1
    assert all(tx.payload.startswith(REQUEST_PREFIX) for tx in final_result.transmissions)

    completed = []
    pending = list(final_result.transmissions)
    while pending:
        request = pending.pop(0)
        retransmit = sender.receive("!00000001", request.payload)
        assert len(retransmit.transmissions) == 1
        received = receiver.receive("!00000001", retransmit.transmissions[0].payload)
        pending.extend(received.transmissions)
        completed.extend(received.frames)
    assert completed == [frame]


def test_stalled_assembly_requests_and_recovers_missing_final_fragment():
    now = [100.0]
    sender = FragmentProtocol(fragment_body=10, request_cooldown=5.0, clock=lambda: now[0])
    receiver = FragmentProtocol(fragment_body=10, request_cooldown=5.0, clock=lambda: now[0])
    frame = b"final-fragment!"
    fragments = sender.encode(frame, "^all")

    receiver.receive("!00000001", fragments[0].payload)
    now[0] += 4.0
    receiver.receive("!00000001", fragments[0].payload)  # duplicate is not progress
    now[0] += 1.1
    repair = receiver.poll_repairs()

    assert len(repair.transmissions) == 1
    index = HEADER.unpack_from(fragments[0].payload)[0]
    assert repair.transmissions[0].payload == REQUEST_PREFIX + HEADER.pack(index, 0)
    retransmit = sender.receive("!00000001", repair.transmissions[0].payload)
    assert [item.payload for item in retransmit.transmissions] == [fragments[-1].payload]
    recovered = receiver.receive("!00000001", retransmit.transmissions[0].payload)
    assert recovered.frames == [frame]
    receiver.receive("!00000001", retransmit.transmissions[0].payload)
    now[0] += 5.1
    assert receiver.poll_repairs().transmissions == []


def test_periodic_repairs_are_bounded_and_back_off():
    now = [100.0]
    sender = FragmentProtocol(fragment_body=10, request_cooldown=5.0, clock=lambda: now[0])
    receiver = FragmentProtocol(fragment_body=10, request_cooldown=5.0, clock=lambda: now[0])
    first = sender.encode(b"final-fragment!", "^all")[0]
    receiver.receive("!00000001", first.payload)

    now[0] += 5.1
    assert len(receiver.poll_repairs().transmissions) == 1
    now[0] += 9.9
    assert receiver.poll_repairs().transmissions == []
    now[0] += 0.2
    assert len(receiver.poll_repairs().transmissions) == 1
    now[0] += 19.9
    assert receiver.poll_repairs().transmissions == []
    now[0] += 0.2
    assert len(receiver.poll_repairs().transmissions) == 1
    now[0] += 100.0
    assert receiver.poll_repairs().transmissions == []


def test_new_fragment_progress_can_continue_after_repair_cap():
    now = [100.0]
    sender = FragmentProtocol(fragment_body=10, request_cooldown=5.0, clock=lambda: now[0])
    receiver = FragmentProtocol(fragment_body=10, request_cooldown=5.0, clock=lambda: now[0])
    fragments = sender.encode(b"0123456789abcdefghijKLMNO", "^all")
    receiver.receive("!00000001", fragments[0].payload)

    for delay in (5.1, 10.1, 20.1):
        now[0] += delay
        assert len(receiver.poll_repairs().transmissions) == 1

    progressed = receiver.receive("!00000001", fragments[-1].payload)
    assert len(progressed.transmissions) == 1
    _, requested_position = HEADER.unpack_from(progressed.transmissions[0].payload, len(REQUEST_PREFIX))
    assert requested_position == 2


def test_global_repair_budget_throttles_concurrent_incomplete_frames():
    now = [100.0]
    sender = FragmentProtocol(fragment_body=10, clock=lambda: now[0])
    receiver = FragmentProtocol(
        fragment_body=10,
        request_cooldown=1.0,
        max_repair_requests_per_window=2,
        repair_window=60.0,
        clock=lambda: now[0],
    )

    immediate_repairs = 0
    for suffix in (b"a", b"b", b"c"):
        fragments = sender.encode(b"0123456789abcdefghij" + suffix, "^all")
        receiver.receive("!00000001", fragments[0].payload)
        result = receiver.receive("!00000001", fragments[-1].payload)
        immediate_repairs += len(result.transmissions)

    assert immediate_repairs == 2
    assert receiver.repair_requests == 2
    assert receiver.repair_throttled == 1

    now[0] += 60.1
    assert len(receiver.poll_repairs().transmissions) == 1


def test_receive_can_defer_control_without_consuming_repair_attempt():
    now = [100.0]
    sender = FragmentProtocol(fragment_body=10, clock=lambda: now[0])
    receiver = FragmentProtocol(fragment_body=10, request_cooldown=1.0, clock=lambda: now[0])
    fragments = sender.encode(b"0123456789abcdefghijK", "^all")

    receiver.receive("!00000001", fragments[0].payload)
    deferred = receiver.receive(
        "!00000001", fragments[-1].payload, allow_control=False
    )
    assert deferred.transmissions == []
    assert receiver.repair_requests == 0

    now[0] += 1.1
    assert len(receiver.poll_repairs().transmissions) == 1
    assert receiver.repair_requests == 1


def test_completed_duplicate_is_suppressed():
    sender = FragmentProtocol(fragment_body=20)
    receiver = FragmentProtocol(fragment_body=20)
    fragments = sender.encode(b"duplicate", "^all")
    first = receiver.receive("!00000001", fragments[0].payload)
    second = receiver.receive("!00000001", fragments[0].payload)
    assert first.frames == [b"duplicate"]
    assert second.frames == []


@pytest.mark.parametrize("payload", [b"", b"\x01", HEADER.pack(1, 0), HEADER.pack(1, 1)])
def test_malformed_fragments(payload):
    protocol = FragmentProtocol()
    with pytest.raises(FragmentError):
        protocol.receive("!00000001", payload)


def test_empty_frame_rejected():
    with pytest.raises(FragmentError):
        FragmentProtocol().encode(b"", "^all")
