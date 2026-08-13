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

При версия 0.1.4 първият ред завършва с
`MQTT uplink permission: true` или `false`, прочетено директно от radio-то.

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
от Sideband/Columba `10 Mbps` остават оценка на локалния TCP endpoint, а не на
LoRa сегмента.

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
3. Изчакайте пълно изпразване и изпратете едно изображение 1–4 KiB. Не
   изпращайте втори файл, докато `radio queue` не стане нула.
4. Следете `device TX queue: FREE/MAX free`. Стойност `0/MAX` може да се появи
   временно; scheduler-ът трябва да продължи, когато firmware докладва свободен
   slot, без `full for 45 seconds` error.
5. Изгасете екрана за 60 минути и повторете кратко съобщение на 10, 30 и 60
   минути. Notification трябва да остане, BLE/TCP да не се reconnect-ва
   циклично, а battery drain да се запише за сравнение.

Не използвайте live telephone/PTT като capacity тест. Най-ниският аудио codec
профил и двупосочният overhead надвишават практичния капацитет на този shared
LoRa сегмент.

## Android 0.1.8 reciprocal unicast и ACK telemetry

Този тест е без Linux gateway. Двата Android bridge-а използват отделни
Meshtastic radios и сочат взаимно към Node ID на другото radio.

### Фаза A — pure LoRa, ACK изключен

1. Изключете MQTT на двете radios и спрете Linux `rnsd`.
2. Потвърдете с обикновен Meshtastic DM в двете посоки, че radios се достигат
   и имат актуални NodeInfo/public keys.
3. На phone A задайте `gateway_unicast` и Node ID на radio B; на phone B
   задайте същия режим и Node ID на radio A.
4. Използвайте еднакъв IFAC в двата Reticulum клиента и оставете
   **Request Meshtastic radio ACK** изключено.
5. Направете announce A→B, кратък LXMF текст A→B и отговор B→A.
6. На двата bridge-а очаквайте да растат едновременно:

```text
TX RNS→mesh: ... frames / ... fragments
RX mesh→RNS: ... frames / ... fragments
```

`last` трябва да показва Node ID на другото radio и, когато firmware го
предоставя, `LoRa`, PKI/channel encryption, hops, SNR и RSSI. Редът за radio ACK
трябва да казва `disabled`; LXMF delivery proof продължава да работи независимо.

### Фаза B — pure LoRa, ACK включен

1. Изчакайте radio queue да стане празна.
2. Включете **Request Meshtastic radio ACK for unicast fragments** на двата
   bridge-а и натиснете **Save & start**.
3. Изпратете само един кратък LXMF текст във всяка посока.
4. `confirmed` трябва да нарасне за потвърдените Meshtastic fragments, а
   `pending` да се върне до нула. Броят е по radio fragment, не по LXMF message.
5. Explicit `NAK` с име като `NO_ROUTE`, `MAX_RETRANSMIT`, `PKI_FAILED` или
   `RATE_LIMIT_EXCEEDED` е реална radio грешка. `unknown` означава, че ACK не е
   наблюдаван до timeout; не доказва, че LXMF съобщението не е пристигнало.

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

## След успешния първи тест

Преди по-дълго използване:

1. Задайте еднакви непразни IFAC `network_name` и `passphrase` на Sideband TCP
   интерфейса на Phone A и `[[HQ Meshtastic hub]]` на Linux. Самият Android
   bridge е прозрачен и няма отделни IFAC полета.
2. Използвайте различна IFAC двойка за Linux TCP port 4242 и Phone B.
3. Ограничете LoRa hub-а с `allowed_nodes = !a1b3b3b8`.
4. Оставете Meshtastic ACK изключен при първото измерване; включвайте го само
   ако измерените загуби го налагат, защото всеки ACK увеличава airtime.
5. За чист LoRa тест временно изключете само MQTT на `!8fd13c64`, без да
   изключвате Wi-Fi/TCP PhoneAPI на радиото. На Phone A изключете Wi-Fi и mobile
   data, но оставете Bluetooth. След двупосочен LXMF тест върнете MQTT.
