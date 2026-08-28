# Roadmap след 0.3.0

## Завършено в 0.3.0

- Непривилегирован Linux Gateway Console sidecar без Docker socket и без
  radio/system capabilities.
- Versioned, secret-free `BridgeCapabilitiesV1`, `BridgeStatusV1`,
  `BridgeTrafficSnapshotV1`, `BridgePeerRouteV1` и `BridgeAlertV1` contract.
- Отделни Linux LoRa/LAN/public counters, Meshtastic queue/reassembly telemetry,
  upstream status, LXMD hash/store status, copy и QR.
- Safe env validator, `conservative/balanced/custom` policy, numbered staging,
  explicit host apply, backup, health check и rollback.
- `off/manual/trusted_auto` discovery policy; trusted autoconnect е allowlisted
  и винаги `boundary`.
- Public upstream safety invariant: radio е `internal`; public announces не се
  export-ват автоматично към LoRa.
- Android 0.3.0 read-only API на `127.0.0.1:7823`, видима версия, copy/import
  config без secrets, LoRa safety check и deep link.
- Frozen Meshtastic port 76 protocol и общи binary vectors.
- Upstream-first integration specification за Columba, CrossTalk, RatSpeak и
  Sideband.

## Release gates преди stable

1. Android ↔ Android soak с три radio peers.
2. Android ↔ Linux `auto_multi_peer`, LAN client и LXMD offline short text.
3. 30-minute public announce storm с непроменен LoRa TX counter.
4. BLE/TCP/radio reconnect и screen-off тестове на Pixel и Honor.
5. Двупосочни numbered short LXMF серии, после едно малко image и кратко PTT в
   лабораторна RF среда.
6. Съпоставка на Android diagnostics, Console и `traffic-report`.
7. Подписан release APK, clean upgrade от 0.2.2 и документиран rollback.

Field profile със слаб/асиметричен Meshtastic path измерва загубата, но не
изисква 100% delivery и не се използва за хаотично transport tuning.

## Следващи milestones

### 0.3.x — hardening

- 24-hour Linux/Android soak и bounded telemetry retention.
- Реални latency/reconnect/route-change samples за public upstream-и.
- Първи Columba companion patch и interoperability report.
- Миграционни tests за Android SharedPreferences и signed release pipeline.

Transport алгоритмите не се променят без диагностика, която доказва конкретен
failure mode.

### 0.4 — ecosystem integration

- Columba upstream proposal.
- CrossTalk preset/status adapter.
- RatSpeak и Sideband companion documentation/proposals.
- Machine-readable contract fixtures за external clients.

### По-късно

- Отделен GPL Field Client само ако документирани client gaps останат след
  upstream интеграциите: LXMF, propagation management, path/ping, NomadNet
  browsing, Linux Nomad hosting, external `i2pd`, opt-in isolated `rnsh`, BBS.
- iOS in-process adapter след осигурен тестов iPhone и преминати port 76
  vectors. Standalone cross-app background bridge не се обещава.

## Извън scope

- realtime telephone calls/audio през LoRa;
- гарантиран transport за големи файлове;
- override на Meshtastic duty-cycle;
- автоматичен browser-controlled Docker restart;
- представяне на RF/Meshtastic routing проблем като поправим изцяло от RNS;
- собствена имплементация на Reticulum routing, cryptography или LXMF.
