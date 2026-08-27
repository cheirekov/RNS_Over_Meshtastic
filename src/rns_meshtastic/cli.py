"""Small operational helpers; the actual gateway runs inside rnsd."""

from __future__ import annotations

import argparse
import getpass
import os
import secrets
import shutil
import ssl
import sys
import threading
import time
from datetime import UTC, datetime
from pathlib import Path

from rns_meshtastic.addresses import BROADCAST_ID, format_node_id, parse_node_id
from rns_meshtastic.framing import FragmentProtocol


def _psk_profile(value: bytes) -> str:
    """Describe a channel key without printing reusable key material."""
    key = bytes(value)
    if not key:
        return "none"
    if key == b"\x01":
        return "default (canonical Base64 AQ==)"
    if len(key) in {16, 32}:
        return f"private-{len(key)}"
    return f"nonstandard-{len(key)}"


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
        local_node = interface.nodesByNum.get(node_num) or {}
        device_metrics = local_node.get("deviceMetrics") or {}
        print(f"channel_utilization={device_metrics.get('channelUtilization')!r}")
        print(f"air_util_tx={device_metrics.get('airUtilTx')!r}")
        if args.node_id:
            target_id = format_node_id(args.node_id)
            node = interface.nodesByNum.get(parse_node_id(target_id))
            print(f"node_lookup={target_id}")
            if node is None:
                print("  known=False")
            else:
                user = node.get("user") or {}
                last_heard = node.get("lastHeard")
                last_heard_iso = datetime.fromtimestamp(last_heard, UTC).isoformat() if last_heard else None
                public_key = user.get("publicKey") or node.get("publicKey")
                print("  known=True")
                print(f"  long_name={user.get('longName')!r}")
                print(f"  short_name={user.get('shortName')!r}")
                print(f"  last_heard={last_heard_iso!r}")
                print(f"  snr={node.get('snr')!r}")
                print(f"  hops_away={node.get('hopsAway')!r}")
                print(f"  via_mqtt={node.get('viaMqtt')!r}")
                print(f"  channel={node.get('channel')!r}")
                print(f"  public_key_known={bool(public_key)}")
        local = interface.localNode
        if local is not None and getattr(local, "localConfig", None) is not None:
            from meshtastic.protobuf import config_pb2

            lora = local.localConfig.lora
            region_name = config_pb2.Config.LoRaConfig.RegionCode.Name(lora.region)
            preset_name = config_pb2.Config.LoRaConfig.ModemPreset.Name(lora.modem_preset)
            print(f"region={region_name} ({lora.region})")
            print(f"modem_preset={preset_name} ({lora.modem_preset})")
            print(f"hop_limit={lora.hop_limit}")
            print(f"override_duty_cycle={lora.override_duty_cycle}")
            print(f"config_ok_to_mqtt={lora.config_ok_to_mqtt}")
            print(f"ignore_mqtt={lora.ignore_mqtt}")
        print("channels:")
        from meshtastic.protobuf import channel_pb2

        for channel in getattr(local, "channels", []) or []:
            settings = channel.settings
            role_name = channel_pb2.Channel.Role.Name(channel.role)
            print(
                f"  index={channel.index} role={role_name} ({channel.role}) "
                f"name={settings.name!r} "
                f"psk_profile={_psk_profile(settings.psk)!r} "
                f"uplink={settings.uplink_enabled} downlink={settings.downlink_enabled}"
            )
        module_config = getattr(local, "moduleConfig", None)
        if module_config is not None:
            mqtt = module_config.mqtt
            print("mqtt:")
            print(f"  enabled={mqtt.enabled}")
            print(f"  address={mqtt.address!r}")
            print(f"  root={mqtt.root!r}")
            print(f"  tls_enabled={mqtt.tls_enabled}")
            print(f"  encryption_enabled={mqtt.encryption_enabled}")
            print(f"  json_enabled={mqtt.json_enabled}")
        return 0
    finally:
        # TCPInterface can return while its reader thread is still completing
        # the config callback that starts the first heartbeat. Let that callback
        # settle before closing, then cancel the long-running timer explicitly.
        time.sleep(0.5)
        heartbeat_timer = getattr(interface, "heartbeatTimer", None)
        if heartbeat_timer is not None:
            heartbeat_timer.cancel()
        interface.close()


def _mqtt_password(args: argparse.Namespace) -> str | None:
    password = os.environ.get(args.password_env)
    if args.username and password is None:
        try:
            password = getpass.getpass("MQTT password: ")
        except (EOFError, KeyboardInterrupt):
            raise RuntimeError(f"set {args.password_env} when no interactive terminal is available") from None
    return password


def _mqtt_smoke(args: argparse.Namespace) -> int:
    from rns_meshtastic.transports.mqtt import MqttBackend, MqttConfig

    try:
        password = _mqtt_password(args)
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
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


def _mqtt_radio_probe(args: argparse.Namespace) -> int:
    import paho.mqtt.client as mqtt
    from meshtastic.protobuf import mesh_pb2, mqtt_pb2, portnums_pb2

    try:
        password = _mqtt_password(args)
        source_id = format_node_id(args.source)
        destination_id = format_node_id(args.destination)
    except (RuntimeError, ValueError) as exc:
        print(str(exc), file=sys.stderr)
        return 2
    if source_id == BROADCAST_ID or destination_id == BROADCAST_ID:
        print("radio probe requires unicast source and destination Node IDs", file=sys.stderr)
        return 2
    if not 0 <= args.hops <= 7 or not 0 <= args.channel_index <= 7:
        print("hops and channel-index must be between 0 and 7", file=sys.stderr)
        return 2

    source_num = parse_node_id(source_id)
    destination_num = parse_node_id(destination_id)
    packet_id = secrets.randbelow(0xFFFFFFFE) + 1
    subscribed = threading.Event()
    acked = threading.Event()
    errors: list[str] = []

    def on_connect(client, userdata, flags, reason_code, properties):
        del userdata, flags, properties
        if bool(getattr(reason_code, "is_failure", False)):
            errors.append(f"MQTT connection rejected: {reason_code}")
            return
        client.subscribe(
            f"{args.root.rstrip('/')}/2/e/{args.channel}/+",
            qos=args.qos,
        )

    def on_subscribe(client, userdata, mid, reason_codes, properties):
        del client, userdata, mid, properties
        if any(bool(getattr(code, "is_failure", False)) for code in reason_codes):
            errors.append(f"MQTT subscription rejected: {reason_codes}")
            return
        subscribed.set()

    def on_message(client, userdata, message):
        del client, userdata
        try:
            envelope = mqtt_pb2.ServiceEnvelope.FromString(message.payload)
            if not envelope.HasField("packet") or not envelope.packet.HasField("decoded"):
                return
            packet = envelope.packet
            data = packet.decoded
            if data.portnum != portnums_pb2.PortNum.ROUTING_APP or data.request_id != packet_id:
                return
            routing = mesh_pb2.Routing.FromString(data.payload)
            packet_source = int(getattr(packet, "from")) & 0xFFFFFFFF
            packet_destination = int(packet.to) & 0xFFFFFFFF
            error_name = mesh_pb2.Routing.Error.Name(routing.error_reason)
            print(
                f"routing ACK: gateway={envelope.gateway_id} "
                f"source={format_node_id(packet_source)} "
                f"destination={format_node_id(packet_destination)} error={error_name}"
            )
            if (
                packet_source == destination_num
                and packet_destination == source_num
                and routing.error_reason == mesh_pb2.Routing.Error.NONE
            ):
                acked.set()
        except Exception as exc:
            errors.append(f"ignored invalid MQTT response: {exc}")

    client = mqtt.Client(
        mqtt.CallbackAPIVersion.VERSION2,
        client_id=f"rns-probe-{packet_id:08x}",
        clean_session=True,
    )
    if args.username:
        client.username_pw_set(args.username, password)
    if args.tls:
        certificate_requirement = ssl.CERT_NONE if args.tls_insecure else ssl.CERT_REQUIRED
        client.tls_set(cert_reqs=certificate_requirement)
        client.tls_insecure_set(args.tls_insecure)
    client.on_connect = on_connect
    client.on_subscribe = on_subscribe
    client.on_message = on_message

    try:
        client.connect(args.host, args.port, keepalive=60)
        client.loop_start()
        if not subscribed.wait(args.timeout):
            detail = errors[-1] if errors else "connection/subscription timeout"
            print(f"MQTT radio probe failed: {detail}", file=sys.stderr)
            return 1

        packet = mesh_pb2.MeshPacket()
        setattr(packet, "from", source_num)
        packet.to = destination_num
        packet.id = packet_id
        packet.channel = args.channel_index
        packet.hop_limit = args.hops
        packet.hop_start = args.hops
        packet.want_ack = True
        packet.priority = mesh_pb2.MeshPacket.Priority.RELIABLE
        packet.decoded.portnum = portnums_pb2.PortNum.RETICULUM_TUNNEL_APP
        packet.decoded.payload = b"rns-mqtt-lora-probe-" + secrets.token_bytes(8)
        packet.decoded.bitfield = 1  # Sender permits MQTT forwarding.

        envelope = mqtt_pb2.ServiceEnvelope()
        envelope.packet.CopyFrom(packet)
        envelope.channel_id = args.channel
        envelope.gateway_id = source_id
        topic = f"{args.root.rstrip('/')}/2/e/{args.channel}/{source_id}"
        publish = client.publish(
            topic,
            envelope.SerializeToString(),
            qos=args.qos,
            retain=False,
        )
        publish.wait_for_publish(args.timeout)
        print(
            f"probe published: id={packet_id} source={source_id} "
            f"destination={destination_id} hops={args.hops} retain=false"
        )
        if not acked.wait(args.timeout):
            detail = errors[-1] if errors else "no matching routing ACK"
            print(f"MQTT radio probe failed: {detail}", file=sys.stderr)
            return 1
        print("MQTT radio probe passed")
        return 0
    finally:
        client.disconnect()
        client.loop_stop()


def _traffic_report(args: argparse.Namespace) -> int:
    from rns_meshtastic.traffic_report import traffic_report

    try:
        output = traffic_report(
            Path(args.config_dir),
            Path(args.baseline_file),
            save_baseline_only=args.save_baseline,
        )
    except (OSError, RuntimeError, ValueError) as error:
        print(str(error), file=sys.stderr)
        return 1
    print(output)
    return 0


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
    info.add_argument("--node-id", help="show safe NodeDB reachability metadata")
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

    probe = sub.add_parser(
        "mqtt-radio-probe",
        help="send an MQTT downlink probe and require a LoRa routing ACK",
    )
    probe.add_argument("--host", required=True)
    probe.add_argument("--port", type=int, default=1883)
    probe.add_argument("--root", required=True)
    probe.add_argument("--channel", required=True)
    probe.add_argument("--channel-index", type=int, required=True)
    probe.add_argument("--source", required=True, help="virtual Meshtastic Node ID")
    probe.add_argument("--destination", required=True, help="physical Meshtastic Node ID")
    probe.add_argument("--hops", type=int, default=0)
    probe.add_argument("--username")
    probe.add_argument("--password-env", default="MESHTASTIC_MQTT_PASSWORD")
    probe.add_argument("--qos", type=int, choices=(0, 1, 2), default=1)
    probe.add_argument("--tls", action="store_true")
    probe.add_argument("--tls-insecure", action="store_true")
    probe.add_argument("--timeout", type=float, default=30.0)
    probe.set_defaults(func=_mqtt_radio_probe)

    traffic = sub.add_parser(
        "traffic-report",
        help="compare LoRa, public-boundary and private TCP RNS traffic",
    )
    traffic.add_argument("--config-dir", default="/data/rns")
    traffic.add_argument("--baseline-file", default="/data/rns/traffic-baseline.json")
    traffic.add_argument(
        "--save-baseline",
        action="store_true",
        help="replace the baseline with current counters instead of reporting",
    )
    traffic.set_defaults(func=_traffic_report)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    return int(args.func(args))


if __name__ == "__main__":
    raise SystemExit(main())
