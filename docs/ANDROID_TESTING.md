# Android bridge: точен тест с два телефона

Тази последователност е успешно изпълнена на 12 август 2026 г. с Pixel 6 Pro,
T-LoRa Pager `!a1b3b3b8`, HQ channel index 0 и Linux gateway radio
`!8fd13c64`. Sideband и Columba обмениха announce-и и двупосочни LXMF
съобщения през BLE/LoRa bridge-а.

Целта на този тест е точно първоначалният сценарий:

```text
Phone A + Meshtastic radio
  Sideband ─TCP/localhost─ Android bridge ─LoRa DM─ !8fd13c64
      ─Reticulum transport on NixOS─ TCP/LAN or VPN ─ Sideband on Phone B
```

Phone B няма нужда от Meshtastic устройство. MQTT не участва в този тест.

## 1. Build и инсталиране

От корена на проекта:

```bash
cd android
docker compose build android-build
docker compose run --rm android-build
```

Очакваният край е `BUILD SUCCESSFUL`. APK файлът е:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

Ако вече имате `adb`:

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

`adb` не е необходим за самата работа: APK може да се копира на Phone A и да
се инсталира след разрешаване на съответния install source.

## 2. NixOS/Linux gateway

Подгответе отделна runtime конфигурация:

```bash
mkdir -p var/android-gateway/interfaces
cp examples/android-gateway/config.example var/android-gateway/config
uv run rns-meshtastic install-interface --config-dir var/android-gateway
```

В `var/android-gateway/config` заменете и четирите `CHANGE-ME` стойности с две
различни двойки дълги случайни IFAC secrets. Не променяйте засега:

```ini
tcp_host = 172.16.16.115
channel_index = 0
mesh_mode = gateway_unicast
gateway_role = hub
```

Спрете Meshtastic CLI/GUI, ако държи TCP PhoneAPI връзка към радиото, и
стартирайте gateway:

```bash
uv run rnsd --config ./var/android-gateway -vv
```

Оставете този терминал отворен. `!8fd13c64` е физическият HQ gateway. Вградената
му MQTT връзка може да остане включена, но не е пътят, който проверяваме тук.

## 3. Phone A: Android bridge

APK 0.1.7 е първата container build версия със запазван debug signing key.
Ако Android откаже update от по-ранна APK с `App not installed`/signature
conflict, запишете текущите полета, uninstall-нете старата APK и инсталирайте
0.1.7. Следващите build-ове могат да се инсталират с update, докато Docker
volume-ът `android-debug-signing` не бъде изтрит.

За първия реален тест използвайте T-LoRa Pager `!a1b3b3b8` през BLE:

1. Disconnect-нете pager-а от официалното Meshtastic приложение. Не е нужно да
   unpair-вате устройството.
2. Отворете **RNS Meshtastic Bridge** и изберете `ble`.
3. Натиснете **Scan for Meshtastic BLE radios**, изберете pager-а и разрешете
   `Nearby devices` и notifications. От версия 0.1.1 изборът на резултат от
   scan автоматично превключва `Radio transport = ble`; TCP host/port стават
   неактивни и стойностите им не се използват.
4. Задайте:

```text
Local Reticulum TCP port = 7822
Meshtastic channel index = 0        # HQ
Hop limit = 3
Radio addressing mode = gateway_unicast
Unicast peer / gateway Meshtastic Node ID = !8fd13c64
Fragment payload bytes = 200
Global delay between Meshtastic transmissions = 2000 ms
Request ACK = off
```

5. Натиснете **Save & start**. Не продължавайте, докато status-ът не съдържа:

```text
BLE PhoneAPI handshake complete as !a1b3b3b8
Reticulum listening on 127.0.0.1:7822
```

При версия 0.1.13 първият ред завършва с
`OK-to-MQTT permission: true` или `false`, прочетено директно от radio-то.
Това е разрешение за uplink, а не доказателство за активна MQTT broker сесия.

Само появата на име/MAC в scan-а не означава BLE връзка. Системният Android
Bluetooth екран също не е надежден индикатор за активна GATT сесия. Решаващият
индикатор е точно `BLE ... !a1b3b3b8` в bridge status-а. Ако се покаже
`TCP ... !8fd1336c`, трафикът минава през Wi-Fi radio `172.16.19.176`.

Версия 0.1.2 показва последователно GATT connect, service discovery, MTU,
notification setup и `MyNodeInfo`. Ако Sideband изпрати announce преди края на
handshake-а, frame-ът чака до 45 секунди вместо да бъде отхвърлен с
`radio identity is not available yet`. За най-чистия диагностичен опит първо
спрете Sideband, стартирайте bridge-а самостоятелно и включете Sideband едва
след `BLE PhoneAPI handshake complete`.

Версия 0.1.3 приема автоматично PKI-криптираните Meshtastic DM пакети към
локалното radio и при избран secondary channel. Firmware 2.7 докладва тези
пакети през PhoneAPI с `channel = 0`, независимо от избрания локален slot.
По-старият bridge ги отхвърляше при `Meshtastic channel index != 0`, въпреки че
LoRa/MQTT доставката до radio-то беше успешна.

Версия 0.1.4 чете `config.lora.config_ok_to_mqtt` от PhoneAPI handshake-а и
копира това разрешение в `Data.bitfield` на изходящите port 76 пакети. Така
auto-PKI DM носи изрично намерението на собственика и последващите MQTT gateways
могат да го спазят. Bridge-ът не разрешава MQTT безусловно — при
`config_ok_to_mqtt = false` bit-ът остава изключен и privacy изборът на radio-то
се запазва. Това е protocol hardening, а не индикатор за активна broker сесия.

`mqtt.enabled = true` означава само, че клиентът е конфигуриран, не че в момента
има активна broker сесия. След включване или рестарт изчакайте MQTT reconnect;
при disconnect firmware queue-ва пакетите. Липсата на `mesh→RNS` не трябва да
се диагностицира само по Android броячите.

Версия 0.1.7 прилага зададения delay глобално между всички Meshtastic
предавания, включително между два отделни едноfragmentни Reticulum frame-а.
Status-ът показва `radio queue` във frames/fragment-и/байтове, приблизителен
`drain`, `device TX queue`, брояч `backpressure` и `dropped`. PhoneAPI
`queue_status` спира подаването, когато firmware TX опашката е пълна. Показаните
от Sideband/Columba `10 Mbps` са фиксираната оценка на локалния TCP endpoint, а
не на LoRa сегмента. RNS използва оценката и за начални timeout-и, затова
Android 0.1.11 държи само около 8 секунди radio работа и прилага TCP
backpressure близо до входа. `backpressure` вече може нормално да нараства при
burst и не означава загуба; `dropped` трябва да остане нула.

За недвусмислен BLE тест след появата на `BLE PhoneAPI handshake complete as
!a1b3b3b8` изключете Wi-Fi и mobile data само на Phone A. Loopback
`127.0.0.1` и BLE продължават да работят. Ако announce/LXMF обменът остане
работещ, Android телефонът не може да използва TCP radio `172.16.19.176`.

Алтернативен първи тест без BLE е `tcp` към Wi-Fi Meshtastic radio на Phone A:
задайте неговия IP и port `4403`. Не насочвайте Android bridge и Linux gateway
едновременно към едно и също TCP radio.

## 4. Phone A: Sideband към локалния bridge

За диагностичния тест оставете само един Reticulum клиент активен на Phone A.
Bridge-ът поддържа една loopback TCP сесия; ако Sideband и Columba се опитват
едновременно да използват port 7822, по-новата връзка заменя предишната.

В Sideband отворете **Connectivity**, включете **Connect via TCP** и задайте:

```text
TCP Host = 127.0.0.1
TCP Port = 7822
Optional IFAC network name = network_name от [[HQ Meshtastic hub]]
Optional IFAC passphrase = passphrase от [[HQ Meshtastic hub]]
TCP Interface Mode = Full
```

IFAC стойностите трябва да съвпадат byte-for-byte. Не включвайте Reticulum
Transport на мобилния телефон. Рестартирайте Sideband/RNS service, защото
Sideband прилага connectivity промените след restart. Bridge status трябва да
се промени на `Reticulum client connected from loopback`.

Sideband използва 128-bit IFAC за TCP. В Linux конфигурацията на
`[[HQ Meshtastic hub]]` оставете `ifac_size = 128` (стойността в config е в
битове). Версии на проекта преди поправката от 12 август 2026 г. използваха
грешен 64-bit default и пакетите се отхвърляха тихо въпреки растящите bridge
броячи.

## 5. Phone B: Sideband без Meshtastic radio

Phone B трябва да има IP достъп до NixOS gateway през LAN или VPN. В Sideband
задайте:

```text
Connect via TCP = on
TCP Host = LAN_OR_VPN_IP_OF_NIXOS
TCP Port = 4242
Optional IFAC network name = network_name от [[LAN VPN Sideband clients]]
Optional IFAC passphrase = passphrase от [[LAN VPN Sideband clients]]
TCP Interface Mode = Full
```

Рестартирайте Sideband/RNS service. Port 4242 трябва да бъде позволен от
firewall само за LAN/VPN; не го port-forward-вайте в публичния Internet.

## 6. Доказване на end-to-end LXMF

1. На Phone B направете announce от Sideband.
2. Изчакайте Phone A да види announce-а. При MediumFast и няколко LoRa hop-а
   това не е моментално.
3. Изпратете кратко LXMF съобщение от Phone A към Phone B, например
   `RNS over HQ test 1`.
4. Отговорете от Phone B към Phone A.

Успех означава едновременно:

- Android status counters `RNS→mesh` и `mesh→RNS` се увеличават;
- Linux log съдържа
  `created Reticulum peer interface for !a1b3b3b8`;
- и двете LXMF съобщения пристигат.

След първия успешен пакет uncomment-нете в gateway конфигурацията:

```ini
allowed_nodes = !a1b3b3b8
```

и рестартирайте `rnsd`.

## Диагностика по слоеве

### Android 0.1.12 remote-path matrix

0.1.12 не променя radio поведението. Новите редове трябва да се снимат и на
двата края преди и след всяка серия:

```text
reassembly: ... active, ... awaiting final, ... missing; completed: ...,
  repair REQ: ..., retransmits: ..., expired: ..., duplicates: ...
admission: ... rejected, ... send-failed; last reject: ...
```

За тест между местния BLE radio и radio в друг град използвайте първо
`gateway_unicast`, `fragment body = 200`, `delay = 2000 ms`, ACK `critical` и
изключен duty-cycle override. Не започвайте с изображение или crossing traffic.

1. **Endpoint MQTT baseline:** включете MQTT на двата крайни radio възела.
   Изпратете `MQ-A-COLD`, изчакайте до 90 s, после `MQ-A-WARM`; повторете в
   обратната посока. Това минимизира зависимостта от неизвестната междуградска
   LoRa връзка.
2. **Local RF ingress:** изключете MQTT само на местния endpoint, оставете
   близкия публичен gateway и отдалечения endpoint/MQTT активни. Повторете с
   `GW-A-COLD/WARM` и обратно. Това измерва LoRa → nearby gateway → MQTT →
   remote radio, но е доказано само ако remote metadata отчете MQTT.
3. Едва след две чисти one-way серии изпратете пет кратки текста в една посока
   с 15 s между тях, после пет в обратната. Crossing серията е последна.

Приемлив кратък run има `admission rejected = 0`, `send-failed = 0`, без
`DUTY_CYCLE_LIMIT`, и `active/awaiting final/missing` се връщат до нула.
Нарастващо `awaiting final` локализира изгубен final fragment; `missing > 0` с
растящ `repair REQ` показва работеща, но натоварена repair логика; `expired > 0`
означава незавършен frame. Ако RX frames растат, но LXMF не се появява,
проблемът вече е над bridge reassembly слоя.

Не използвайте изображения за тази матрица. Sideband Backbone/TCP може да
подаде multi-KiB RNS resource frame, който не се побира в кратката LoRa queue.
0.1.12 ще го покаже като `admission ... exceeds admission limit`; това е
Reticulum-facing MTU/bitrate проблем, не резултат от междуградския radio тест.

### Android 0.1.13 combined reconnect acceptance — резултат

Този тест замени допълнителните MQTT permutation серии:

1. На двата края задайте `gateway_unicast`, body `200`, delay `2000 ms` и ACK
   `critical`. Потвърдете с по един уникален кратък текст във всяка посока.
2. На единия телефон спрете само Sideband/Columba; Android bridge-ът и radio-то
   трябва да останат активни.
3. От другия край изпратете един уникален кратък текст и изчакайте status-ът на
   приемащия bridge да покаже `inbound spool: 1/... frames`.
4. Стартирайте отново същия Reticulum client. Очаквайте
   `replayed 1 buffered frame(s)`, spool `0`, `rejected: 0` и съобщението в
   клиента. Не изпращайте втори пакет, докато replay-ят не приключи.
5. Ако последният RX ред е `MQTT→LoRa`, това означава MQTT произход с финален
   LoRa ingress. Само `MQTT` означава директно предаване от firmware MQTT
   транспорта; `OK-to-MQTT permission: true` само разрешава uplink.

Тестът покрива path label, кратък client disconnect, FIFO replay и новите
delivery counters. Петминутният volatile spool не е offline mailbox; за
устойчиво store-and-forward се използва LXMF propagation node (`lxmd`).

Полевият вариант беше изпълнен в broadcast/slot 1. Резултатът показа
`replayed 10 buffered frame(s)`, 15 общо replayed,
spool `expired: 0` и `rejected: 0`. Spool-ът е приел и подал завършените RNS
frames правилно. Reassembly едновременно показа 9 expired assemblies на
единия bridge и `3 active, 3 awaiting final` на другия. Това доказва реална
загуба на legacy final fragments преди spool-а и е най-силното обяснение за
липсващия текст, но opaque RNS transport-ът не може да съпостави конкретен
encrypted LXMF текст с конкретно assembly само от тези снимки.

### Android 0.1.15 bounded-repair regression

Инсталирайте 0.1.15 и на двата Android края. При Android↔Linux обновете кода и
рестартирайте `rnsd`; стар sender не разбира optional `REQ position 0` и няма да
върне final fragment.

Използвайте последната работеща конфигурация — включително broadcast/slot 1 и
MQTT, ако така е измерен пътят. Не повтаряйте MQTT on/off матрицата и не
изпращайте изображение:

1. Изпратете три уникални кратки текста само в една посока, с 15 секунди между
   тях, след което изчакайте 90 секунди. Повторете веднъж в обратната посока.
2. Очакваният краен резултат е трите текста да пристигнат, `active`,
   `awaiting final` и `missing` да се върнат до zero, без admission/device/spool
   reject.
3. Ако бъде изгубен final fragment, status-ът трябва да увеличи
   `repair REQ: ... (final: N)`, последван от `retransmits`, и assembly-то да
   завърши. Ако `final` остане zero, run-ът пак е валиден, но RF пътят не е
   активирал тази repair branch; детерминистичните unit тестове я покриват.

При прекъснат/асиметричен път една unresolved позиция може да увеличи `repair
REQ` най-много с три и после да се появи в `capped`; не трябва да се наблюдават
десетки повтарящи се REQ/NAK за едно assembly. В broadcast режим всички radio
ACK са изключени, включително за unicast repair request/retransmit. При
gateway-unicast `critical` продължава да защитава final и repair packets.

Това е единственият необходим кратък field regression преди background soak.

| Симптом | Най-вероятна причина |
|---|---|
| BLE radio не се вижда | още е свързано с Meshtastic app или не advertising-ва |
| Scan намира pager-а, но status показва TCP | избран/останaл е TCP transport; използвайте bridge 0.1.1 |
| `identity is not available yet` с bridge 0.1.1 | PhoneAPI handshake още не е върнал `MyNodeInfo`; използвайте 0.1.2 |
| `BLE handshake timeout: no MyNodeInfo` | pairing/GATT handshake не е завършил; запишете последната предходна BLE фаза |
| HQ/slot 0 работи, но secondary slot не приема unicast | използвайте bridge 0.1.3; PKI DM пакетите се докладват от firmware с channel 0 |
| LoRa DM работи, но същият auto-PKI DM не се вижда в MQTT | bridge 0.1.4 показва и пренася `config_ok_to_mqtt`; отделно потвърдете реална MQTT broker връзка |
| `mqtt.enabled = true`, но няма broker packets | MQTT сесията на radio-то още не е свързана; това не е доказателство за bridge/Reticulum дефект |
| Sideband не се свързва | bridge service не работи или port-ът не е 7822 |
| Frames растат само RNS→mesh | грешен HQ PSK/index, gateway ID или няма RF route |
| Port 76 стига Linux, но RNS го отхвърля | IFAC name/passphrase или размерът му не съвпадат; за Sideband използвайте `ifac_size = 128` |
| Phone B не се вижда | firewall/VPN/IFAC проблем на TCP 4242 |
| Фрагменти се губят | увеличете delay първо на 3000–4000 ms |

Broadcast fallback се тества чрез `Radio addressing mode = broadcast` на
Android и `mesh_mode = broadcast`, `gateway_role = client` на Linux интерфейса.
Не смесвайте единия край в broadcast, а другия в gateway-unicast.

За директен тест с два Android телефона, две Meshtastic радиа и без Linux
gateway използвайте [DIRECT_ANDROID_BROADCAST.md](DIRECT_ANDROID_BROADCAST.md).
Android 0.1.7 включва опционалния allowlist, bounded radio queue, глобален
pacing и видими drain/backpressure/dropped counters.

### 0.1.7 queue и power regression

След краткия двупосочен LXMF тест:

1. Уверете се, че `radio queue` се връща до `0 frames, 0 fragments, 0 bytes`,
   `dropped: 0` и няма нов `last error`.
2. Изпратете последователно 10 кратки текста. `radio queue` може временно да
   расте, а `backpressure` може да се увеличи — това е нормално ограничаване на
   бързия loopback TCP producer. `dropped` трябва да остане `0`.
3. Не използвайте изображение като queue acceptance с актуален Sideband
   Backbone client. Дори малък файл може да бъде опакован в multi-KiB RNS
   resource frame. При отделен диагностичен опит следете 0.1.12 `admission`;
   всеки reject прави крайния resource transfer невалиден.
4. Следете `device TX queue: FREE/MAX free`. Стойност `0/MAX` може да се появи
   временно; scheduler-ът трябва да продължи, когато firmware докладва свободен
   slot, без `full for 45 seconds` error.
5. Изгасете екрана за 60 минути и повторете кратко съобщение на 10, 30 и 60
   минути. Notification трябва да остане, BLE/TCP да не се reconnect-ва
   циклично, а battery drain да се запише за сравнение.

Не използвайте live telephone/PTT като capacity тест. Най-ниският аудио codec
профил и двупосочният overhead надвишават практичния капацитет на този shared
LoRa сегмент.

## Android 0.1.12 reciprocal unicast, backpressure и ACK telemetry

Този тест е без Linux gateway. Двата Android bridge-а използват отделни
Meshtastic radios и сочат взаимно към Node ID на другото radio.

Използвайте 0.1.12 и на двата телефона. Версията изчаква успешния BLE GATT write
callback, преди да отчете fragment като предаден към radio. `local retries`
трябва да остане 0 при стабилна връзка; увеличение означава локален BLE/TCP
handoff retry, а не RF retransmission.

Преди теста проверете duty-cycle състоянието. За `EU_868` Meshtastic прилага
10% TX airtime върху rolling 60-minute прозорец. Не включвайте
`override_duty_cycle`. При TCP radio, докато bridge-ът е спрян, изпълнете:

```bash
uv run rns-meshtastic radio-info --tcp-host RADIO_IP
```

Запишете `air_util_tx`, `channel_utilization` и
`override_duty_cycle=False`. При BLE-only radio вижте Device Metrics в
Meshtastic клиента, след което го разкачете преди bridge-а. За сравнителен тест
започнете с `air_util_tx < 5%` и idle `channel_utilization < 10%`, за да има
достатъчно headroom. Channel utilization е кратък rolling прозорец и не е
същата метрика като часовия TX duty cycle. Поставете теста на пауза при 25% и
го прекратете при 40%; първо изчакайте поне 90 секунди без нов LXMF трафик и
измерете новия idle baseline. Ако bridge status покаже
`last: DUTY_CYCLE_LIMIT (9)`, прекратете серията и изчакайте срока, посочен от
firmware; това не е RF загуба и не трябва да се заобикаля с override.

### Фаза A — pure LoRa, ACK изключен

1. Изключете MQTT на двете radios и спрете Linux `rnsd`.
2. Потвърдете с обикновен Meshtastic DM в двете посоки, че radios се достигат
   и имат актуални NodeInfo/public keys.
3. На phone A задайте `gateway_unicast` и Node ID на radio B; на phone B
   задайте същия режим и Node ID на radio A.
4. Използвайте еднакъв IFAC в двата Reticulum клиента и оставете
   `Meshtastic radio ACK policy = off`.
5. Направете announce A→B, кратък LXMF текст A→B и отговор B→A.
6. На двата bridge-а очаквайте да растат едновременно:

```text
TX RNS→mesh: ... frames / ... fragments
RX mesh→RNS: ... frames / ... fragments
```

`last` трябва да показва Node ID на другото radio и, когато firmware го
предоставя, `LoRa`, PKI/channel encryption, hops, SNR и RSSI. Редът за radio ACK
трябва да казва `disabled`; LXMF delivery proof продължава да работи независимо.

### Фаза B — pure LoRa, critical ACK

1. Изчакайте radio queue да стане празна и на двата телефона. Не започвайте при
   ненулев `drain` или докато предишни LXMF proofs още се връщат.
2. Изберете `Meshtastic radio ACK policy = critical` на двата bridge-а и
   натиснете **Save & start**. Не използвайте `all`; той е само диагностичен
   stress режим и вече е показал тежко congestion поведение.
3. Изчакайте отново PhoneAPI handshake и `Reticulum client connected from
   loopback` и на двата края.
4. Изпратете само един кратък LXMF текст A→B. Изчакайте queue и `pending` да
   станат нула и запишете двата status блока. Едва тогава изпратете един текст
   B→A и повторете записа. Не изпращайте двете посоки едновременно.
5. `confirmed` трябва да нарасне за потвърдените Meshtastic fragments, а
   `pending` да се върне до нула. Броят е по radio fragment, не по LXMF message.
6. Explicit `NAK` с име като `NO_ROUTE`, `MAX_RETRANSMIT`, `PKI_FAILED` или
   `RATE_LIMIT_EXCEEDED` е реална radio грешка. `unknown` означава, че ACK не е
   наблюдаван до timeout; не доказва, че LXMF съобщението не е пристигнало.
7. Запишете `local retries` поотделно на BLE и TCP края. Стойност над нула на
   BLE края доказва локален GATT handoff проблем; `confirmed/NAK/unknown`
   описват следващия Meshtastic radio слой.

Запишете за всеки опит двата bridge status блока и действителния LXMF delivery
status. Това позволява да сравним radio ACK с крайния Reticulum/LXMF резултат.

### Фаза C — MQTT-assisted повторение

1. Включете MQTT и **Encryption enabled** на radios според използвания broker;
   channel uplink/downlink и `config_ok_to_mqtt` трябва да разрешават трафика.
2. Уверете се първо с Meshtastic DM, че MQTT-assisted маршрутът работи.
3. Повторете по един кратък LXMF текст с ACK включен.
4. Очаквайте `last` да показва `MQTT`, когато `via_mqtt`/transport metadata е
   налична. Сравнете confirmed/NAK/unknown с pure-LoRa резултата.

Едва след тези кратки тестове изпратете един файл 1–4 KiB при празна queue.
Не сравнявайте файлове, докато short-message тестът не е записан и в двете
посоки.

### Фаза D — 0.1.16 bounded-congestion regression

Този тест проверява containment, а не максимална пропускателна способност. Не
създавайте нарочно 50% channel utilization. Инсталирайте 0.1.16 и на двата
телефона, използвайте reciprocal `gateway_unicast`, ACK policy `adaptive`, 200
bytes и 2000 ms. При празни queues изпратете `A1`, след около 5 секунди `B1`,
после `A2` и `B2` със същия интервал. Прекратете серията при 25% ChUtil.

След поне 120 секунди без нов трафик натиснете **Copy diagnostics** на двата
bridge-а. При слаб/асиметричен път е допустимо LXMF proof да закъснее или да
липсва, но следните transport invariants трябва да останат верни:

- `repair flow` не надхвърля `12/12 requests in rolling minute`; `throttled`
  може да расте, без да се създава неограничена REQ буря;
- `queue-deferred` може да расте при пълна queue, но не изразходва repair опит;
- `admission: data ...` остава отделно от `control ...`; control reject вече не
  се отчита като изгубен RNS data frame;
- при лош ACK return path `radio ACK (adaptive; suppressed ...)` спира да товари
  ефира за пет минути; това не означава LXMF failure;
- след изчакването radio queue и device TX queue трябва да се изпразнят.

### Фаза E — доказуем pure-LoRa с 0.1.18

Тази фаза се прави отделно от adaptive congestion теста. На двата Android
bridge-а задайте `MQTT forwarding permission for bridge packets = force_off`
и ACK policy `off`, натиснете **Save & start** и проверете преди изпращане:

```text
MQTT forwarding: force_off → denied
```

Настройката не променя radio MQTT module, channel uplink/downlink или
`config_ok_to_mqtt`; тя само премахва OK-to-MQTT разрешението от port 76
пакетите на bridge-а. Изпратете един кратък текст A→B, изчакайте queue да се
изпразни, после един B→A. Приетите port 76 пакети трябва да показват `LoRa`, а
не `MQTT` или `MQTT→LoRa`. Ако едната посока не мине, върнете `inherit`; това е
доказателство, че работещият маршрут зависи от MQTT gateway, а не причина да се
увеличава duty cycle или repair budget.

Новият `peak` на radio queue и `peak used` на device queue остават видими до
рестарт. Копирайте diagnostics след теста, дори текущите queues вече да са
празни. `RNS frame mix` показва дали bridge-ът е пренесъл data, announce, link
или proof рамки, а `radio activity (last 60s)` показва действително изпратените
data/repair Meshtastic фрагменти в същия rolling прозорец като ChUtil.

За контролиран idle baseline стартирайте първо само bridge-а без
Sideband/Columba, изчакайте 70 секунди и копирайте diagnostics. Очакването е
`TX 0 fragments/0 B` за последните 60 секунди. После свържете Reticulum клиента,
без announce или чат, изчакайте нови 70 секунди и копирайте втори report. Ако
тогава TX вече не е нула, трафикът е генериран от Reticulum клиента/неговата
локална RNS instance, а не от BLE, TCP PhoneAPI или чужд Meshtastic трафик.

### Фаза F — 0.1.20 FIFO + queue pacing A/B

Повторете same-room pure-LoRa setup от Фаза E с `constrained_auto` на двата
bridge-а. Оставете body 200, base interval 2000 ms, hop 0, broadcast, ACK off и
`force_off`. Един announce от всеки клиент е достатъчен; не изтривайте
контактите. След това изпратете един кратък текст A→B и един B→A, без burst.

Диагностиката трябва да показва `traffic scheduling: constrained_auto` и
`scheduler: RNS FIFO causal order`. `queue pacing ... last +N ms` може да
стане ненулево при firmware queue occupancy; това е order-preserving scheduler
delay, не Meshtastic duty-cycle override. Нито data, proof, link, нито announce
може да изпревари по-рано приет RNS frame. Acceptance:

- frame/fragment mix на TX край съвпада с RX на отсрещния край;
- няма data/control reject, local retry, repair или expired assembly;
- `peak used` е по-нисък от 0.1.18 baseline 11/16 или поне не достига full;
- краткият текст получава крайния RNS/LXMF proof и в двете посоки.

При regression изберете `transparent` на двата bridge-а. Той запазва новата
диагностика и същия FIFO ред, но изключва soft QueueStatus pacing, тоест дава
пряко сравнение със scheduler поведението на 0.1.18.

### Фаза G — 0.1.21 auto single-peer

Тази фаза използва същите две radio устройства в една стая и остава pure-LoRa:
`force_off`, channel slot 1, hop limit 0, body 200, interval 2000 ms,
`constrained_auto` и ACK `off`. На двата bridge-а изберете
`auto_single_peer`; в peer полето на A въведете Node ID на B, а на B — Node ID
на A.

1. Стартирайте двата bridge-а и двата Reticulum клиента.
2. Направете един announce само от A и изчакайте да се появи при B.
3. Направете един announce само от B и изчакайте да се появи при A.
4. Изпратете един кратък текст A→B и изчакайте delivery proof.
5. Изпратете един кратък текст B→A и изчакайте delivery proof.
6. Копирайте diagnostics преди спиране на клиента или bridge-а.

Acceptance:

- topology съдържа `auto single-peer (announce broadcast, other RNS unicast)`;
- `addressing broadcast/unicast` има поне един broadcast frame и ненулев
  unicast брой на всеки край;
- последните data/proof packets са `PKI` и са адресирани към локалния radio;
- и двете съобщения и proof-ове пристигат;
- няма admission/device reject, local retry, drop или expired assembly;
- `scheduler: RNS FIFO causal order` остава активен.

Този режим няма broadcast retry при radio ACK timeout и не научава други
peer-ове. При regression върнете двата bridge-а директно на доказания
`broadcast` setup; не променяйте едновременно pacing, ACK или MQTT policy.

### Фаза H — 0.2.0 auto multi-peer

Първият тест е без IFAC и по възможност с три radio устройства в една стая.
На всички bridge-ове задайте `auto_multi_peer`, еднакъв Meshtastic channel,
body `200`, interval `2000`, hop `0`, `constrained_auto`, ACK `off` и
`force_off`. Полето за unicast peer е неактивно. За discovery baseline оставете
allowlist празен; след успешния тест повторете с Node ID-тата на другите два
radio peer-а във всеки bridge.

1. Стартирайте A, B и C и направете по един RNS announce последователно, с пауза
   до появата му на другите клиенти.
2. Изпратете по един кратък текст A→B, B→C и C→A, като изчакате proof преди
   следващия.
3. Направете crossing burst само след успешните единични съобщения.
4. Копирайте diagnostics от трите bridge-а преди restart.

Acceptance: `peer table` показва останалите radio Node ID-та; `peer routing`
има learned routes; announce броят увеличава broadcast, а data/proof след
learning увеличават unicast; няма conflict, admission reject, incomplete
assembly или expired frame. Първият packet към още непознат destination може
да е `unknown broadcast` — това е discovery fallback, не retry след ACK timeout.

Повторение с IFAC е отделен compatibility тест. Очаквайте `opaque-ifac
broadcasts` да расте и трафикът да остане broadcast; не очаквайте learned
unicast routing от скрит RNS header.

### Фаза I — 0.2.0 serialized small-resource test

Използвайте два radio peer-а в една стая, `auto_single_peer`, pure LoRa
`force_off`, hop `0`, body `200`, interval `2000`, ACK `off` и без паралелен
message burst. След успешен кратък текст изпратете последователно приблизително
1 KiB, 4 KiB и 8 KiB resource, като винаги чакате `radio queue` да стане 0 и
крайния LXMF status преди следващия.

Diagnostics показва normal и `serialized bulk` limits, active/accepted count,
largest RNS frame и приблизителния drain. Лимитът е за единичен RNS frame, не
за файла; клиентски overhead може да направи 8 KiB attachment по-голям от 8
KiB frame. При `oversize rejected` кадърът е спрян локално и не трябва да има
нови Meshtastic TX fragments. При serialized acceptance очаквайте около 2 s на
fragment (8 KiB ≈ 41 fragments ≈ 82 s), плюс proof/repair и adaptive pacing.
Следете ChUtil и прекратете теста при регулаторен/device reject; bridge-ът не
включва duty-cycle override.

### Записан laboratory acceptance — 2026-08-16

Фази H и I са приети за два bridge-а в една стая, pure LoRa, channel 1,
`force_off`, `constrained_auto`, ACK `off` и без IFAC. `auto_multi_peer`
научи другия radio и в двете посоки: 96/158 active routes, нула conflicts,
нула unknown fallback и 92/75 learned-unicast TX frames след discovery.

Кратки съобщения, изображения и PTT са получени с end-to-end confirmations.
Най-големите outbound RNS frames са 3747 B/19 fragments и 7907 B/40 fragments;
serialized admission е приел 2 и 3 large frames. И на двата края има:

- `oversize rejected: 0` и data/control admission `0/0`;
- `missing 0`, `expired 0`, `capped 0` и `duplicates 0`;
- firmware reject `0`, local retry `0` и dropped `0`;
- празна reassembly и radio queue в края.

`peak 19/40 fragments` включва целия единствен serialized frame и може
умишлено да е по-голям от normal denominator `/4`. `backpressure 58/55` е
успешно ограничаване на local TCP producer-а, не packet loss. Honor е replay-нал
9 frames, а Pixel 1 след кратки local-client прекъсвания, без spool rejection.
Pixel BLE transport е реконектнал два пъти (`radio up/down 3/2`); следващият
field report трябва да отбележи дали reconnect има при screen-off и дали
съвпада с delivery delay, repair или drop.

Следващият приемателен тест е градският маршрут: един TCP bridge близо до
`gorna2`, другият BLE bridge в движение, първо кратки текстове, после само един
малък resource. Записват се hop count, LoRa/MQTT path label, RSSI/SNR, reconnect
events, queue drain, ChUtil, repairs и proofs. Не смесвайте едновременно голям
resource, PTT и burst — така евентуалната грешка остава локализируема.

## След успешния първи тест

Преди по-дълго използване:

1. Задайте еднакви непразни IFAC `network_name` и `passphrase` на Sideband TCP
   интерфейса на Phone A и `[[HQ Meshtastic hub]]` на Linux. Самият Android
   bridge е прозрачен и няма отделни IFAC полета.
2. Използвайте различна IFAC двойка за Linux TCP port 4242 и Phone B.
3. Ограничете LoRa hub-а с `allowed_nodes = !a1b3b3b8`.
4. Оставете Meshtastic ACK изключен при първото измерване; включвайте го само
   ако измерените загуби го налагат, защото всеки ACK увеличава airtime.
5. За чист LoRa тест използвайте `force_off` на bridge packet-ите. Самото
   изключване на MQTT client на крайното radio не спира друг gateway да uplink-не
   packet с OK-to-MQTT permission. След теста върнете `inherit`.
