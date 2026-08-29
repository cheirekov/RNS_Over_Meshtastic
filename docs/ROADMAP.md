# Roadmap след 0.5.0

## Завършено в Linux Console 0.5.0

- Basic authentication е задължителна при non-loopback publishing; health
  endpoint-ът остава минимален и без secrets.
- Structured Add/Remove public и private upstream-и, отделен private IFAC и
  отделни counters/status за private boundaries.
- Public/private bootstrap subsets за allowlisted `trusted_auto` discovery,
  configurable stamp value, gravity и opt-in probe response.
- Header-only LoRa announce observability и ясно състояние преди да бъде научен
  първият dynamic Meshtastic peer; IFAC ciphertext остава изрично opaque.

Android остава 0.4.0; transport, scheduler и repair алгоритмите не са променяни.

## Завършено в 0.4.0

- Gateway Console с human-readable traffic/rates, точни API/Prometheus bytes,
  реални или изрично unavailable LXMD counters и LAN/queue visibility.
- Schema-driven Basic/Advanced configuration за всички managed variables;
  secret-preserving staging и contextual help.
- Bounded structured event journal, delta/current alerts и manual LXMD announce
  през тесен Unix control socket с persistent cooldown.
- Android `versionCode 28`; release task без production signing identity вече
  прекъсва, а Docker workflow build-ва, проверява и архивира подписания APK.
- Decision-complete Columba direct-interface specification с TCP/BLE етапи,
  interoperability gates и изрично MPL/GPL/maintainer решение преди код.

Transport framing, `auto_multi_peer`, scheduler и repair алгоритмите не са
променени в този milestone.

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
7. Подписан release APK, clean install, upgrade със същия ключ и документиран rollback.

Field profile със слаб/асиметричен Meshtastic path измерва загубата, но не
изисква 100% delivery и не се използва за хаотично transport tuning.

## Следващи milestones

### 0.4.x — acceptance и hardening

- 24-hour Linux/Android soak и bounded telemetry retention.
- Реални latency/reconnect/route-change samples за public upstream-и.
- Production-key signed APK clean-install/upgrade evidence.
- Първи Columba companion patch и interoperability report.
- MPL/GPL решение с Columba maintainer преди direct-interface код.
- Миграционни tests за Android SharedPreferences.

Transport алгоритмите не се променят без диагностика, която доказва конкретен
failure mode.

### 0.5 — ecosystem integration

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
