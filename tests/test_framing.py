import pytest

from rns_meshtastic.framing import HEADER, REQUEST_PREFIX, FragmentError, FragmentProtocol


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
    assert len(final_result.transmissions) == 2
    assert all(tx.payload.startswith(REQUEST_PREFIX) for tx in final_result.transmissions)

    completed = []
    for request in final_result.transmissions:
        retransmit = sender.receive("!00000001", request.payload)
        assert len(retransmit.transmissions) == 1
        completed.extend(receiver.receive("!00000001", retransmit.transmissions[0].payload).frames)
    assert completed == [frame]


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
