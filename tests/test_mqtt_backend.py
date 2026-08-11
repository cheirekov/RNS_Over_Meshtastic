from types import SimpleNamespace

from meshtastic.protobuf import mqtt_pb2, portnums_pb2

from rns_meshtastic.transports.mqtt import MqttBackend, MqttConfig


class FakePublishInfo:
    rc = 0


class FakeClient:
    def __init__(self):
        self.published = []
        self.subscriptions = []

    def publish(self, topic, payload, qos, retain):
        self.published.append((topic, payload, qos, retain))
        return FakePublishInfo()

    def subscribe(self, topic, qos):
        self.subscriptions.append((topic, qos))


def make_backend():
    backend = MqttBackend(
        MqttConfig(
            host="127.0.0.1",
            virtual_node_id="!aabbcc11",
            channel_name="RNS",
            root="rns-test/EU_868",
            downlink_hop_limit=3,
        )
    )
    backend._client = FakeClient()
    backend._connected.set()
    return backend


def test_mqtt_send_encodes_binary_service_envelope():
    backend = make_backend()
    backend.send(b"fragment", "!11223344")
    topic, raw, qos, retain = backend._client.published[0]
    envelope = mqtt_pb2.ServiceEnvelope.FromString(raw)

    assert topic == "rns-test/EU_868/2/e/RNS/!aabbcc11"
    assert envelope.channel_id == "RNS"
    assert envelope.gateway_id == "!aabbcc11"
    assert getattr(envelope.packet, "from") == 0xAABBCC11
    assert envelope.packet.to == 0x11223344
    assert envelope.packet.hop_limit == 3
    assert envelope.packet.decoded.portnum == portnums_pb2.PortNum.RETICULUM_TUNNEL_APP
    assert envelope.packet.decoded.payload == b"fragment"
    assert qos == 1
    assert retain is False


def test_mqtt_receive_delivers_and_deduplicates():
    sender = make_backend()
    sender.send(b"fragment", "^all")
    _, raw, _, _ = sender._client.published[0]
    envelope = mqtt_pb2.ServiceEnvelope.FromString(raw)
    envelope.gateway_id = "!99887766"
    raw = envelope.SerializeToString()

    receiver = MqttBackend(MqttConfig(host="127.0.0.1", virtual_node_id="!11223344", channel_name="RNS"))
    received = []
    states = []
    receiver._packet_callback = lambda source, destination, payload: received.append(
        (source, destination, payload)
    )
    receiver._state_callback = lambda online, detail: states.append((online, detail))
    message = SimpleNamespace(payload=raw)
    receiver._on_message(None, None, message)
    receiver._on_message(None, None, message)

    assert received == [("!aabbcc11", "^all", b"fragment")]
    assert states == []


def test_mqtt_connect_accepts_paho_reason_code_object():
    backend = MqttBackend(MqttConfig(host="127.0.0.1", virtual_node_id="!11223344", channel_name="RNS"))
    client = FakeClient()
    states = []
    backend._state_callback = lambda online, detail: states.append((online, detail))
    reason_code = SimpleNamespace(value=0, is_failure=False)

    backend._on_connect(client, None, None, reason_code, None)

    assert backend._connected.is_set()
    assert client.subscriptions == [("msh/EU_868/2/e/RNS/+", 1)]
    assert states == [(True, None)]
