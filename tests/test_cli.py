from rns_meshtastic.cli import build_parser


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
