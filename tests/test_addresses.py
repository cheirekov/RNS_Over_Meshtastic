import pytest

from rns_meshtastic.addresses import BROADCAST_NUM, format_node_id, parse_node_id


@pytest.mark.parametrize(
    ("value", "expected"),
    [
        ("!aabbcc11", 0xAABBCC11),
        ("!AABBCC11", 0xAABBCC11),
        ("0xaabbcc11", 0xAABBCC11),
        ("^all", BROADCAST_NUM),
        (1, 1),
    ],
)
def test_parse_node_id(value, expected):
    assert parse_node_id(value) == expected


def test_format_node_id():
    assert format_node_id(0xAABBCC11) == "!aabbcc11"
    assert format_node_id(BROADCAST_NUM) == "^all"


@pytest.mark.parametrize("value", ["aabbcc11", "!abc", -1, 0x1_0000_0000])
def test_invalid_node_id(value):
    with pytest.raises(ValueError):
        parse_node_id(value)
