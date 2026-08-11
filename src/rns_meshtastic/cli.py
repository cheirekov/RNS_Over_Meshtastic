"""Small operational helpers; the actual gateway runs inside rnsd."""

from __future__ import annotations

import argparse
import os
import secrets
import shutil
import sys
import threading
import time
from pathlib import Path

from rns_meshtastic.addresses import format_node_id
from rns_meshtastic.framing import FragmentProtocol


def _install_interface(args: argparse.Namespace) -> int:
    target_dir = Path(args.config_dir).expanduser().resolve() / "interfaces"
    target_dir.mkdir(parents=True, exist_ok=True)
    source = Path(__file__).resolve().parents[2] / "interfaces" / "RNSMeshtasticInterface.py"
    if not source.is_file():
        print(f"loader shim not found at {source}", file=sys.stderr)
        return 1
    target = target_dir / source.name
    shutil.copyfile(source, target)
    print(target)
    return 0


def _fragment_selftest(args: argparse.Namespace) -> int:
    del args
    sender = FragmentProtocol(fragment_body=37)
    receiver = FragmentProtocol(fragment_body=37)
    frame = bytes(range(256)) + b"reticulum-over-meshtastic"
    fragments = sender.encode(frame, "!aabbcc11")
    completed: list[bytes] = []
    for fragment in reversed(fragments):
        completed.extend(receiver.receive("!11223344", fragment.payload).frames)
    if completed != [frame]:
        print("fragment self-test failed", file=sys.stderr)
        return 1
    print(f"fragment self-test passed: {len(frame)} bytes in {len(fragments)} fragments")
    return 0


def _radio_info(args: argparse.Namespace) -> int:
    if args.tcp_host:
        from meshtastic.tcp_interface import TCPInterface

        interface = TCPInterface(hostname=args.tcp_host, portNumber=args.tcp_port)
    elif args.serial_port:
        from meshtastic.serial_interface import SerialInterface

        interface = SerialInterface(devPath=args.serial_port)
    else:
        print("provide --tcp-host or --serial-port", file=sys.stderr)
        return 2
    try:
        my_info = interface.myInfo
        node_num = int(my_info.my_node_num) & 0xFFFFFFFF
        print(f"node_id={format_node_id(node_num)}")
        metadata = getattr(interface, "metadata", None)
        if metadata is not None:
            print(f"firmware={getattr(metadata, 'firmware_version', 'unknown')}")
        print(f"known_nodes={len(interface.nodesByNum)}")
        local = interface.localNode
        if local is not None and getattr(local, "localConfig", None) is not None:
            lora = local.localConfig.lora
            print(f"region={lora.region}")
            print(f"modem_preset={lora.modem_preset}")
            print(f"hop_limit={lora.hop_limit}")
        print("channels:")
        for channel in getattr(local, "channels", []) or []:
            settings = channel.settings
            print(
                f"  index={channel.index} role={channel.role} name={settings.name!r} "
                f"uplink={settings.uplink_enabled} downlink={settings.downlink_enabled}"
            )
        return 0
    finally:
        interface.close()
        time.sleep(0.25)


def _mqtt_smoke(args: argparse.Namespace) -> int:
    from rns_meshtastic.transports.mqtt import MqttBackend, MqttConfig

    password = os.environ.get(args.password_env)
    if args.username and password is None:
        print(
            f"set the {args.password_env} environment variable for MQTT authentication",
            file=sys.stderr,
        )
        return 2

    channel = f"RNSX{secrets.token_hex(3).upper()}"
    payload = b"rns-meshtastic-smoke-" + secrets.token_bytes(12)
    connected_a = threading.Event()
    connected_b = threading.Event()
    received = threading.Event()
    errors: list[str] = []

    def state_callback(event: threading.Event):
        def callback(up: bool, error: str | None) -> None:
            if up:
                event.set()
            elif error:
                errors.append(error)

        return callback

    def packet_callback(source: str, destination: str, data: bytes) -> None:
        if source == "!ee000001" and destination == "^all" and data == payload:
            received.set()

    common = dict(
        host=args.host,
        port=args.port,
        root=args.root,
        channel_name=channel,
        username=args.username,
        password=password,
        tls=args.tls,
        tls_insecure=args.tls_insecure,
        downlink_hop_limit=0,
    )
    sender = MqttBackend(MqttConfig(virtual_node_id="!ee000001", **common))
    receiver = MqttBackend(MqttConfig(virtual_node_id="!ee000002", **common))
    try:
        sender.start(lambda *_: None, state_callback(connected_a))
        receiver.start(packet_callback, state_callback(connected_b))
        if not connected_a.wait(args.timeout) or not connected_b.wait(args.timeout):
            detail = errors[-1] if errors else "connection timeout"
            print(f"MQTT smoke test failed: {detail}", file=sys.stderr)
            return 1
        sender.send(payload, "^all")
        if not received.wait(args.timeout):
            detail = errors[-1] if errors else "published packet was not received"
            print(f"MQTT smoke test failed: {detail}", file=sys.stderr)
            return 1
        print(f"MQTT smoke test passed: channel={channel} bytes={len(payload)} retain=false hops=0")
        return 0
    finally:
        sender.close()
        receiver.close()


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="rns-meshtastic")
    sub = parser.add_subparsers(dest="command", required=True)

    install = sub.add_parser("install-interface", help="install the rnsd loader shim")
    install.add_argument("--config-dir", required=True)
    install.set_defaults(func=_install_interface)

    selftest = sub.add_parser("fragment-selftest", help="test framing without a radio")
    selftest.set_defaults(func=_fragment_selftest)

    info = sub.add_parser("radio-info", help="read radio identity and LoRa configuration")
    info.add_argument("--tcp-host")
    info.add_argument("--tcp-port", type=int, default=4403)
    info.add_argument("--serial-port")
    info.set_defaults(func=_radio_info)

    smoke = sub.add_parser(
        "mqtt-smoke",
        help="test binary ServiceEnvelope publish/subscribe without a radio",
    )
    smoke.add_argument("--host", required=True)
    smoke.add_argument("--port", type=int, default=1883)
    smoke.add_argument("--root", required=True)
    smoke.add_argument("--username")
    smoke.add_argument("--password-env", default="MESHTASTIC_MQTT_PASSWORD")
    smoke.add_argument("--tls", action="store_true")
    smoke.add_argument(
        "--tls-insecure",
        action="store_true",
        help="disable certificate verification; diagnostic use only",
    )
    smoke.add_argument("--timeout", type=float, default=10.0)
    smoke.set_defaults(func=_mqtt_smoke)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    return int(args.func(args))


if __name__ == "__main__":
    raise SystemExit(main())
