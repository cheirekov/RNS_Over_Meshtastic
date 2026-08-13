# Капацитет, опашки и няколко радиа

## Къде трябва да има опашка

Има три различни слоя и не трябва да се смесват:

| Слой | Подходяща функция | Постоянен storage |
|---|---|---|
| Meshtastic bridge | кратка bounded TX опашка, pacing, retry на fragment | не |
| Reticulum | announce/path/link/resource механизми | само собственото RNS състояние |
| LXMF application | offline mailbox и store-and-forward | да, чрез Propagation Node |

Суров Reticulum frame не носи за bridge-а надеждна семантика „текст“, „файл“
или „локация“. Записването му на диск и replay след рестарт може да изпрати
остарял path/link пакет, да заеме ефира и да бъде отхвърлено като duplicate.
Затова Android 0.1.7 използва bounded RAM queue с отделни лимити за RNS frames,
Meshtastic fragment-и и байтове. При `tx_interval = 2000` fragment лимитът
покрива приблизително две минути radio drain; след това loopback TCP четенето
прилага backpressure вместо тихо изхвърляне. Малък резерв остава достъпен за
missing-fragment request/retransmit трафика. Linux интерфейсът също има bounded
RAM priority queue.

Android bridge-ът следи и PhoneAPI `queue_status`. Ако малката вътрешна TX
опашка на Meshtastic устройството няма свободен слот, scheduler-ът изчаква нов
status, вместо да продължи да подава пакети към firmware-а.

Устойчивото буфериране на съобщения трябва да се добави като отделен Linux
LXMF Propagation Node. Това е следващ самостоятелен milestone; не изисква
промяна на wire protocol-а между bridge-а и Meshtastic.

## Реалистични payload-и

| Функция | Оценка за Meshtastic LoRa сегмент |
|---|---|
| Кратък LXMF текст | основният и най-подходящ случай |
| Рядка локация/telemetry | възможно, с голям интервал и промяна-предизвикано изпращане |
| Малък файл | възможно като контролиран Resource тест, но бавно и с quota |
| Voice note | възможно само като малък store-and-forward файл след измерване |
| Live PTT/LXST | технически протоколът съществува, но не е разумна цел за shared MediumFast LoRa mesh |

Bridge-ът не може професионално да приоритизира „локация пред файл“, защото
Reticulum трафикът е криптиран и transport слоят е application-agnostic.
Приоритети и quotas по тип съдържание се правят в клиента/LXMF услугата преди
пакетът да стигне интерфейса.

Първият файлов тест трябва да е малък (например 1–4 KiB), единичен и при празна
radio queue. След него се измерват действителна продължителност, retransmissions и
packet loss, преди да се избере quota.

## Повече радиа на Linux сървъра

Няколко радиа на един и същ frequency/preset/channel не са bandwidth
load-balancer. Те споделят collision domain и регулаторния airtime; round-robin
на fragment-и може да влоши доставката.

Полезните модели са:

1. **Active/passive redundancy** — едно radio предава, останалите са standby.
2. **Receive diversity** — приемане от няколко географски gateway-а с packet
   deduplication, но само един избран transmitter.
3. **Независими сегменти** — различни RF channels/географии като отделни RNS
   interfaces; тогава Reticulum избира пътища между реално различни връзки.

Бъдещ `MultiRadioBackend` трябва да има receive deduplication, health score,
active-transmitter election и отделни duty/queue метрики. Не трябва да изпраща
всяко копие през всички радиа и не трябва да разпределя fragment-и на случаен
принцип.
