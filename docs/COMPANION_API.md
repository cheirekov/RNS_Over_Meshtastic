# Bridge companion contract v1

Companion API позволява на Reticulum клиенти да разберат, че локалният TCP hop
води към constrained LoRa transport. API е read-only, versioned и не връща
radio host, IFAC, channel keys или други credentials.

## Android

Android bridge 0.3.0 запазва raw RNS listener на `127.0.0.1:7822` и слуша само
на IPv4 loopback за HTTP:

```text
GET http://127.0.0.1:7823/v1/capabilities
GET http://127.0.0.1:7823/v1/status
GET http://127.0.0.1:7823/v1/traffic
GET http://127.0.0.1:7823/v1/peers
```

Няма POST/PUT/DELETE endpoint. Порт 7823 е резервиран и не може да бъде избран
за raw RNS listener. Deep link `rnsmeshtastic://settings` отваря настройките.

`BridgeCapabilitiesV1` включва schema/version, raw RNS и status ports,
`constrained_transport: true`, `realtime_supported: false`, maximum serialized
RNS frame, Meshtastic PortNum 76 и addressing modes.

`BridgeStatusV1` описва lifecycle, policy, topology и alerts.
`BridgeTrafficSnapshotV1` разделя `lora`, `lan`, `public` и `propagation` с
`rx_bytes`, `tx_bytes`, `rx_bps`, `tx_bps`. Android не измерва public/LXMD
сегмент и връща нули за тях. Допълнителните frame/fragment/queue fields са
implementation-specific, но backward-compatible в schema 1.

`BridgePeerRouteV1` използва radio Node ID, route count и last-seen когато
измерването е налично. Клиентът трябва да игнорира непознати JSON полета.

## Linux

Linux използва същите структури през `/api/v1/capabilities` и вложените
`status.traffic`, `status.peers`, `status.alerts`. Linux Console е на отделен
операторски port 8787 и не е автоматично достъпен от Android приложения.

## Client behavior

Когато `constrained_transport=true`, клиентът трябва:

- да показва LoRa/constrained badge вместо локалните TCP `10 Mbps`;
- да не предлага realtime call като нормална услуга;
- да предупреждава преди PTT, image или resource transfer;
- да показва приблизителен airtime/queue cost, а не TCP throughput;
- да запази LXMF/Reticulum proof като delivery authority. Meshtastic ACK е
  само диагностичен резултат за radio packet.

Schema 1 е additive: смисълът и типът на съществуващо поле не се променят.
Breaking change изисква нов `/v2` endpoint и ново име на contract-а.
Machine-readable основата е в
[bridge-contract-v1.schema.json](bridge-contract-v1.schema.json).
