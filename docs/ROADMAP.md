# Post-MVP roadmap

Този документ фиксира договорената последователност след функционалния MVP.
Новите идеи първо се съпоставят с нея, за да не смесваме radio transport,
Reticulum routing и LXMF application отговорности.

## Доказана основа

- Linux/NixOS gateway-unicast с Reticulum child interface за Android peer.
- Android ↔ Android channel broadcast без Linux.
- Android ↔ Android фиксиран Meshtastic unicast без Linux; двата bridge-а
  сочат взаимно към Node ID на другото radio.
- Pure LoRa и LoRa–MQTT–LoRa port 76 пътища.
- Sideband и Columba, със и без Reticulum IFAC.
- Bounded Android queue, global pacing, PhoneAPI flow control и foreground
  background operation.

## Приоритет 0 — наблюдаемост и delivery semantics

Android 0.1.8:

- показва отделни TX и RX frame/fragment counters и последния входящ
  Meshtastic peer;
- декодира RF/MQTT, PKI, channel, hop, SNR и RSSI metadata, когато firmware я
  предоставя;
- приема `ROUTING_APP` ACK/NAK и го корелира по `request_id` с реалния
  изходящ Meshtastic packet ID;
- различава `confirmed`, explicit `NAK` и timeout `unknown`; липсващ ACK никога
  не се представя автоматично като недоставено LXMF съобщение;
- изисква unicast port 76 пакетът да е адресиран към локалното radio;
- нарича конфигурационния адрес `unicast peer / gateway`, понеже peer може да е
  Linux или друг Android bridge.

Оставащият acceptance test е описан в `ANDROID_TESTING.md`: ACK off/on върху
pure LoRa и след това върху MQTT-assisted път.

## Приоритет 1 — измерена delivery policy

Преди автоматизация се записват за кратък frame, няколко fragments и единичен
1–4 KiB файл:

- време за доставка;
- Meshtastic ACK, NAK и unknown резултати;
- fragment repair requests и retransmissions;
- radio queue peak, backpressure и duplicates;
- Reticulum/LXMF delivery status независимо от radio ACK.

Meshtastic ACK потвърждава конкретен radio fragment. Reticulum proof и LXMF
receipt остават по-високите и авторитетни нива за крайна доставка.

## Приоритет 2 — ограничен auto режим

Първата безопасна цел е `auto_single_peer`:

1. ограничен broadcast за discovery при липса на peer;
2. unicast към един конфигуриран или надеждно научен peer;
3. fragment repair и Reticulum proof за надеждност;
4. без автоматичен broadcast retry само защото ACK е изтекъл — ACK отговорът
   също може да бъде изгубен.

Bridge-ът не класифицира текст, файл, локация или voice note. RNS/IFAC payload
може да е криптиран и transport слоят не трябва да разбира LXMF съдържание.
Приоритети и quotas по съдържание принадлежат на клиента/LXMF услугата.

Пълен multi-peer auto режим изисква peer-aware Reticulum interfaces на Android,
подобни на Linux hub child interfaces; това не е малка промяна на spinner-а.

## Приоритет 3 — optional Linux service profile

Linux остава незадължителен за директна Android връзка, но може да комбинира
отделни услуги:

- `rnsd` Transport Node за маршрути, announces и forwarding;
- `RNSMeshtasticInterface` за едно или повече реално отделени radio сегменти;
- `lxmd` Propagation Node за криптиран LXMF offline store-and-forward;
- persistent volume, storage/transfer quotas, diagnostics и backup;
- TCP listener само върху LAN/VPN.

`lxmd` трябва да е отделен process/container, използващ същата RNS instance,
а не storage функция вътре в Android bridge или replay на сурови RNS frames.
Връзка по IP към Linux се конфигурира като отделен Reticulum interface в
клиента, когато той го поддържа; не се скрива вътре в radio bridge-а.

## Следващи, но не текущи задачи

- Android multi-peer child-interface архитектура;
- Linux multi-radio active/passive failover и receive diversity;
- release packaging, NixOS service и production diagnostics;
- по-широки Android OEM и firmware soak тестове.

## Извън текущия обхват

- обещание за гарантирана доставка върху LoRa;
- live voice/PTT и големи media/file transfers;
- автоматичен broadcast flood като fallback;
- disk replay на opaque Reticulum frames;
- same-channel radio bandwidth bonding;
- custom Meshtastic firmware fork;
- поставяне на LXMF mailbox логика в transport bridge-а.
