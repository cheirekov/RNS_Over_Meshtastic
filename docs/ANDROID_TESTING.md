# Android bridge: точен тест с два телефона

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

За първия реален тест използвайте T-LoRa Pager `!a1b3b3b8` през BLE:

1. Disconnect-нете pager-а от официалното Meshtastic приложение. Не е нужно да
   unpair-вате устройството.
2. Отворете **RNS Meshtastic Bridge** и изберете `ble`.
3. Натиснете **Scan for Meshtastic BLE radios**, изберете pager-а и разрешете
   `Nearby devices` и notifications.
4. Задайте:

```text
Local Reticulum TCP port = 7822
Meshtastic channel index = 0        # HQ
Hop limit = 3
Radio addressing mode = gateway_unicast
Linux gateway Node ID = !8fd13c64
Fragment payload bytes = 200
Delay between fragments = 2000 ms
Request ACK = off
```

5. Натиснете **Save & start**. Не продължавайте, докато status-ът не съдържа:

```text
BLE PhoneAPI handshake complete as !a1b3b3b8
Reticulum listening on 127.0.0.1:7822
```

Алтернативен първи тест без BLE е `tcp` към Wi-Fi Meshtastic radio на Phone A:
задайте неговия IP и port `4403`. Не насочвайте Android bridge и Linux gateway
едновременно към едно и също TCP radio.

## 4. Phone A: Sideband към локалния bridge

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
| `identity is not available yet` | PhoneAPI handshake още не е върнал `MyNodeInfo` |
| Sideband не се свързва | bridge service не работи или port-ът не е 7822 |
| Frames растат само RNS→mesh | грешен HQ PSK/index, gateway ID или няма RF route |
| Port 76 стига Linux, но RNS го отхвърля | IFAC network name/passphrase не съвпадат |
| Phone B не се вижда | firewall/VPN/IFAC проблем на TCP 4242 |
| Фрагменти се губят | увеличете delay първо на 3000–4000 ms |

Broadcast fallback се тества чрез `Radio addressing mode = broadcast` на
Android и `mesh_mode = broadcast`, `gateway_role = client` на Linux интерфейса.
Не смесвайте единия край в broadcast, а другия в gateway-unicast.
