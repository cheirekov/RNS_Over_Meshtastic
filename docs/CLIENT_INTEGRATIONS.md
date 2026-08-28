# Client integrations

Стратегията е upstream-first: малък preset/companion patch, без копиране на
bridge transport или Reticulum cryptography в клиента. Постоянен fork се
поддържа само ако upstream не може да приеме самостоятелната интеграция.

## Общ integration flow

1. Провери `http://127.0.0.1:7823/v1/capabilities` с кратък timeout.
2. Ако endpoint-ът отговори със schema 1, предложи preset
   **RNS over Meshtastic companion** към `127.0.0.1:7822`.
3. Покажи constrained-LoRa badge и изключи/предупреди за realtime UI.
4. Използвай `/v1/traffic` и `/v1/status` само за UI/diagnostics.
5. Не извеждай Meshtastic ACK като LXMF delivery receipt.
6. При липсващ API продължи като обикновен RNS TCP client; това запазва
   съвместимост със стар bridge.

## Columba — първи pilot

Непосредственият upstream-safe patch е companion preset, loopback capability
probe, constrained badge и resource/PTT warning. Директният in-process
Meshtastic interface има decision-complete технически дизайн, но е блокиран от
изрично повдигнатата MPL/GPL граница, докато maintainer-ите не одобрят лицензен
или clean-room път. Виж [COLUMBA_DIRECT_INTERFACE.md](COLUMBA_DIRECT_INTERFACE.md).

## CrossTalk

Linux/desktop preset сочи към gateway TCP listener. По желание чете Linux
Console API от VPN/localhost и показва radio/upstream health. GPL bridge код не
се копира в клиента; процесите остават отделни.

## RatSpeak

Първата интеграция е същият TCP companion preset. Native Rust port 76 adapter
се разглежда само след interoperability с frozen vectors и ясна причина да не
се използва външният bridge.

## Sideband

Поддържаме готова настройка за loopback TCP и deep link
`rnsmeshtastic://settings`. Понеже public GitHub е mirror, основният deliverable
е документация и малък patch proposal към канала, използван от upstream.

## Release gate за upstream patch

- няма credentials в logs/telemetry;
- стар bridge без companion API продължава да работи;
- client не представя 10 Mbps TCP hop като LoRa capacity;
- short LXMF, announce и proof regression преминават;
- предупреждение преди image/PTT, без забрана на експерименталната функция;
- API parsing игнорира непознати additive fields.

Собствен Field Client остава отделен GPL-3.0-or-later проект след стабилен
Console и поне една приета външна интеграция. Първият target е Linux, после
Android. iOS остава отложен до налично устройство.
