from rns_meshtastic.rns_metadata import ANNOUNCE_PACKET_TYPE, is_opaque_ifac, parse_rns_frame


def _packet(*, header_type: int = 0, packet_type: int = ANNOUNCE_PACKET_TYPE) -> bytes:
    destination_offset = 2 if header_type == 0 else 18
    frame = bytearray(destination_offset + 16 + 1 + 8)
    frame[0] = (header_type << 6) | packet_type
    if header_type:
        frame[2:18] = b"\x22" * 16
    frame[destination_offset : destination_offset + 16] = b"\x11" * 16
    frame[destination_offset + 16] = 0x44
    return bytes(frame)


def test_parses_only_content_free_rns_header_metadata() -> None:
    metadata = parse_rns_frame(_packet(header_type=1))
    assert metadata is not None
    assert metadata.is_announce
    assert metadata.destination_hash == "11" * 16
    assert metadata.context == 0x44


def test_ifac_ciphertext_remains_opaque() -> None:
    frame = bytearray(_packet())
    frame[0] |= 0x80
    assert is_opaque_ifac(frame)
    assert parse_rns_frame(frame) is None
