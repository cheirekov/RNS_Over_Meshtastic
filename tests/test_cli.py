from rns_meshtastic.cli import _psk_profile, build_parser


def test_psk_profile_never_returns_key_material():
    assert _psk_profile(b"") == "none"
    assert _psk_profile(b"\x01") == "default (canonical Base64 AQ==)"
    assert _psk_profile(bytes(range(16))) == "private-16"
    assert _psk_profile(bytes(range(32))) == "private-32"
    assert _psk_profile(b"\x01\x0f\xbe") == "nonstandard-3"


def test_mqtt_radio_probe_rejects_broadcast_destination():
    args = build_parser().parse_args(
        [
            "mqtt-radio-probe",
            "--host",
            "127.0.0.1",
            "--root",
            "test",
            "--channel",
            "RNS",
            "--channel-index",
            "0",
            "--source",
            "!ee000001",
            "--destination",
            "^all",
        ]
    )

    assert args.func(args) == 2


def test_mqtt_radio_probe_rejects_invalid_hops():
    args = build_parser().parse_args(
        [
            "mqtt-radio-probe",
            "--host",
            "127.0.0.1",
            "--root",
            "test",
            "--channel",
            "RNS",
            "--channel-index",
            "0",
            "--source",
            "!ee000001",
            "--destination",
            "!11223344",
            "--hops",
            "8",
        ]
    )

    assert args.func(args) == 2
