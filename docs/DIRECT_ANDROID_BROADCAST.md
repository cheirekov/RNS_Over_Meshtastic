# Директен Android ↔ Android тест без Linux gateway

Този тест проверява Meshtastic **channel broadcast**, а не DM/PKI unicast:

```text
Sideband A ─localhost─ Android bridge A ─Meshtastic channel─ bridge B ─localhost─ Sideband B
                                      LoRa и/или MQTT
```

Linux `rnsd` не участва. В Meshtastic няма отделна multicast група за този
случай; адресът на пакета е broadcast (`^all`), а логическата група се определя
от Meshtastic channel-а и Reticulum IFAC-а.

## 1. Radio prerequisites

Двете радиа трябва да имат:

- еднакви region и modem preset;
- channel с **точно еднакви name и PSK**;
- при MQTT път: еднакъв broker/root, `MQTT enabled`, `Encryption enabled`,
  `config_ok_to_mqtt`, както и uplink/downlink за съответния channel.

Локалният channel index може да е различен. Например `Bulgaria` може да е slot
1 на radio A и slot 3 на radio B. Във всеки Android bridge се въвежда локалният
slot на неговото собствено radio.

Стандартният еднобайтов Meshtastic PSK `0x01` се представя канонично като
`AQ==`. Низът `AQ++` декодира до други три байта и не е същият ключ. Не
преписвайте ключ по спомен; сравнете channel QR/URL или прочетете безопасния
профил с:

```bash
uv run rns-meshtastic radio-info --tcp-host RADIO_IP
```

Изход `psk_profile='default (canonical Base64 AQ==)'` доказва само стандартния
ключ. За private key командата показва само дължина и не разкрива ключа.

## 2. Android bridge A и B

Инсталирайте една и съща APK версия (0.1.7 или по-нова) и на двата телефона. На всеки:

1. Свържете bridge-а със собственото radio по BLE или TCP.
2. Изберете `Radio addressing mode = broadcast`.
3. Въведете локалния `Meshtastic channel index` за общия channel.
4. Оставете Meshtastic ACK изключен; broadcast пакетите нямат routing ACK.
5. За първо откриване оставете `Allowed peer Node IDs` празно. След успешен
   двупосочен тест въведете Node ID на другото radio. При повече участници
   въведете comma-separated списък.
6. Запазете и изчакайте status `Meshtastic ready as !...`.

Полето `Gateway Meshtastic Node ID` е неактивно в broadcast режим.

## 3. Sideband A и B

И на двата телефона задайте TCP interface:

```text
Host = 127.0.0.1
Port = 7822
Mode = Full
IFAC network name = една и съща стойност на A и B
IFAC passphrase = една и съща стойност на A и B
```

Android bridge е прозрачен за IFAC; стойностите се задават в Sideband. За
публичен Meshtastic channel непразен еднакъв IFAC е задължителната практическа
защита на Reticulum сегмента.

Не включвайте Reticulum Transport на телефоните за този първи peer-to-peer
тест. Рестартирайте Sideband/RNS service след промяната.

## 4. Контролирана последователност

1. Спрете Linux `rnsd`, за да докажете, че няма междинен gateway.
2. Стартирайте двата Android bridge-а и после двата Sideband клиента.
3. Направете един announce от A. Изчакайте да се появи в B и го добавете.
4. Изпратете кратко LXMF съобщение A→B и отговор B→A.
5. Повторете announce от B към A.

Успех има само ако и двата bridge-а покажат увеличение и на `RNS→mesh`, и на
`mesh→RNS`, а двата LXMF текста пристигнат при спрян Linux gateway.

За чист LoRa тест изключете MQTT само на радиата и оставете BLE активен. За
хибриден LoRa/MQTT/LoRa тест върнете MQTT и проверете отново същата
последователност. Променяйте само една променлива между два теста.

## 5. Ограничения

- Всеки Reticulum frame става Meshtastic channel broadcast и може да бъде
  препредаден от mesh-а; това е по-скъпо като airtime от DM.
- `Allowed peer Node IDs` филтрира в Android след приемането. То намалява
  обработката и RNS injection-а, но не може да върне вече изразходвания LoRa
  airtime.
- Публичният `Bulgaria` channel е подходящ само за кратък функционален тест.
  За постоянен RNS сегмент използвайте отделен private channel плюс IFAC.
- Не правете чести announce-и. Един при стартиране и след това рядко е
  достатъчен за този бавен сегмент.
