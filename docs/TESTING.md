# Тестова процедура

Тестовете са наредени така, че всяка стъпка доказва само един нов слой. Не
започвайте с Sideband, MQTT и два LoRa hop-а едновременно — при грешка няма да е
ясно кой слой я причинява.

## 0. Изолирана среда и автоматични тестове

От корена на проекта:

```bash
devbox shell
uv sync --extra dev
uv run pytest
uv run ruff check .
uv run rns-meshtastic fragment-selftest
```

Очакван резултат:

```text
... passed
fragment self-test passed: ...
```

`uv` създава само локална `.venv` директория. Не инсталира Python библиотеки в
NixOS/host Python.

## 1. Пълен Reticulum тест през локален MQTT broker — без радиа

Този тест проверява `rnsd/rncp → Reticulum interface → fragmentation →
ServiceEnvelope → MQTT → reassembly → Reticulum`.

Стартирайте тестовия broker, достъпен само на `127.0.0.1:18883`:

```bash
docker compose up -d --wait mqtt
```

Покажете destination hash на приемащия `rncp`:

```bash
uv run rncp --config ./examples/mqtt-a -p
```

Запишете стойността `Listening destination`. След това в терминал A:

```bash
mkdir -p /tmp/rns-mqtt-received
uv run rncp --config ./examples/mqtt-a --listen --no-auth -b 0 \
  --save /tmp/rns-mqtt-received -v
```

В терминал B заменете `DESTINATION_HASH` с отпечатаната стойност:

```bash
echo 'Reticulum over binary Meshtastic MQTT' >/tmp/rns-mqtt-test.txt
uv run rncp --config ./examples/mqtt-b \
  /tmp/rns-mqtt-test.txt DESTINATION_HASH -v
```

Проверка:

```bash
cmp /tmp/rns-mqtt-test.txt /tmp/rns-mqtt-received/rns-mqtt-test.txt
```

`cmp` не трябва да отпечата нищо и трябва да завърши с exit code 0. След теста:

```bash
docker compose down
```

## 1a. Локален gateway-unicast/DM тест — без радиа

Това проверява точно логиката „един Meshtastic Node ID = един Reticulum peer
interface“. Стартирайте отново локалния broker:

```bash
docker compose up -d --wait mqtt
uv run rncp --config ./examples/mqtt-hub -p
```

Запишете `Listening destination`. В терминал A:

```bash
mkdir -p /tmp/rns-mqtt-unicast-received
uv run rncp --config ./examples/mqtt-hub --listen --no-auth -b 0 \
  --save /tmp/rns-mqtt-unicast-received -v
```

В терминал B:

```bash
echo 'Reticulum over Meshtastic unicast' >/tmp/rns-mqtt-test.txt
uv run rncp --config ./examples/mqtt-client \
  /tmp/rns-mqtt-test.txt DESTINATION_HASH -v
cmp /tmp/rns-mqtt-test.txt \
  /tmp/rns-mqtt-unicast-received/rns-mqtt-test.txt
docker compose down
```

В log-а на hub-а трябва да има:

```text
created Reticulum peer interface for !b0000002
```

Самият `rncp` resource transfer включва двупосочен обмен, така че успешното
копиране проверява и отговора от hub child interface към правилния virtual node.

## 1b. Smoke test на българския публичен broker — без радиа

Тази проверка използва два временни virtual node-а и случаен channel ID. Тя
публикува един binary `ServiceEnvelope` с `retain=false` и `hop_limit=0`, след
което проверява дали вторият client получава същия port-76 payload:

```bash
uv run rns-meshtastic mqtt-smoke \
  --host mqtt.meshtastic.vip \
  --port 1883 \
  --root msh/Bulgaria \
  --username meshdev
```

Командата пита интерактивно за паролата без да я показва и работи еднакво под
bash, zsh и fish. За automation може вместо това предварително да зададете
environment променливата `MESHTASTIC_MQTT_PASSWORD`.

Очакваният резултат е:

```text
MQTT smoke test passed: channel=RNSX...... bytes=33 retain=false hops=0
```

На 11 август 2026 г. този plaintext тест премина успешно. Той доказва broker
login, subscribe/publish ACL и binary Meshtastic protobuf преноса. Не доказва
MQTT-to-LoRa downlink или липса на zero-hop policy — за това е необходим
физически Meshtastic MQTT gateway с matching channel. Тестовият случаен channel
не трябва да съвпада с реален радио channel, а съобщението не е retained.

## 2. Проверка на реалните Meshtastic радиа

На двете крайни радиа проверете:

- еднакъв region;
- еднакъв modem preset — започнете с `MEDIUM_FAST`;
- еднакъв frequency slot;
- messaging channel с еднакви Name и PSK;
- `Rebroadcast Mode = ALL` на необходимите relay възли;
- да не се използва `CORE_PORTNUMS_ONLY`, защото той не препредава port 76.

Gateway кодът не променя тези настройки.

За Wi-Fi радио:

```bash
uv run rns-meshtastic radio-info --tcp-host RADIO_IP
```

За USB/serial радио:

```bash
uv run rns-meshtastic radio-info --serial-port /dev/ttyACM0
```

Запишете `node_id`, channel index и modem preset за всяко радио. Уверете се, че
към TCP PhoneAPI port 4403 няма друг свързан Meshtastic клиент — firmware приема
само една едновременна TCP PhoneAPI връзка.

## 3. Две радиа: първи broadcast тест

Копирайте примерите в непроследяваната `var/` директория:

```bash
mkdir -p var/gateway/interfaces var/radio-client/interfaces
cp examples/nix-gateway/config.example var/gateway/config
cp examples/native-client/config.example var/radio-client/config
uv run rns-meshtastic install-interface --config-dir var/gateway
uv run rns-meshtastic install-interface --config-dir var/radio-client
```

Редактирайте:

- `tcp_host` за всяко радио;
- `channel_index` според локалния index на съответното радио;
- еднакви `network_name` и `passphrase` на двата Meshtastic интерфейса;
- оставете `mesh_mode = broadcast`;
- за `MEDIUM_FAST` започнете с `tx_interval = 2.0`;
- използвайте малък тестов файл — 100–500 bytes.

Терминал A, NixOS gateway:

```bash
uv run rnsd --config ./var/gateway -vv
```

Терминал B, listener върху същата gateway Reticulum instance:

```bash
uv run rncp --config ./var/gateway -p
uv run rncp --config ./var/gateway --listen --no-auth -b 0 \
  --save /tmp/rns-lora-received -v
```

Терминал C, второто радио, след замяна на hash-а:

```bash
echo 'Reticulum over Meshtastic LoRa' >/tmp/rns-lora-test.txt
uv run rncp --config ./var/radio-client \
  /tmp/rns-lora-test.txt DESTINATION_HASH -v
```

При успех проверете файла с `cmp` и наблюдавайте counters:

```bash
uv run rnstatus --config ./var/gateway -a
```

Ако пакетите се губят, първо увеличете `tx_interval` на 3–4 секунди. Едва след
стабилен тест преминете към `SHORT_FAST` и по-малък интервал.

## 4. Gateway unicast/DM тест

Първо върнете двата възела на работещия broadcast тест и изчакайте да обменят
NodeInfo/public keys. След това променете gateway конфигурацията:

```ini
mesh_mode = gateway_unicast
gateway_role = hub
accept_broadcast_on_hub = No

# Препоръчително след първия тест:
allowed_nodes = !NODE_OF_RADIO_CLIENT
```

Променете client конфигурацията:

```ini
mesh_mode = gateway_unicast
gateway_role = client
gateway_node = !NODE_OF_GATEWAY_RADIO
```

Оставете първия тест с:

```ini
want_ack = No
pki_required = No
```

Това позволява firmware автоматично да използва PKI, ако public key е известен,
и channel PSK fallback, ако още не е. След доказан обмен може да зададете:

```ini
want_ack = Yes
pki_required = Yes
```

`want_ack` увеличава airtime, но помага на Meshtastic 2.6+ да научи next hop.

Стартирайте gateway и изпратете първия файл **от client към gateway**. В gateway
log трябва да се появи:

```text
created Reticulum peer interface for !........
```

След това повторете теста и в обратната посока. Всеки Meshtastic Node ID получава
отделен логически Reticulum child interface, така че отговорът се изпраща като DM
към правилното радио.

## 5. Sideband/Columba през LAN или VPN

Примерната gateway конфигурация слуша на TCP port 4242. Ограничете го с firewall
до LAN/VPN интерфейса. В Reticulum клиента добавете TCP client със следните
стойности:

```text
host          = NIXOS_GATEWAY_LAN_OR_VPN_IP
port          = 4242
network_name  = стойността от [[LAN and VPN clients]]
passphrase    = стойността от [[LAN and VPN clients]]
```

Не препращайте port 4242 през публичния router. Проверете първо, че клиентът се
вижда в:

```bash
uv run rnstatus --config ./var/gateway -a
```

След това изпратете LXMF съобщение към обявена Sideband/Columba identity.

## 6. Реален Meshtastic MQTT gateway и виртуален RNS възел

Използвайте частен или регионален broker, който изрично позволява този трафик.
Копирайте `examples/mqtt-virtual-gateway/config.example`, след което задайте:

- broker host/port/TLS credentials;
- `mqtt_root`, съвпадащ с физическия Meshtastic gateway;
- `mqtt_channel`, съвпадащ с Name на отделния RNS channel;
- уникален `virtual_node_id`;
- `mqtt_downlink_hops` според политиката на broker-а.

На физическия Wi-Fi/Ethernet Meshtastic gateway настройките са еквивалентни на:

```bash
meshtastic --host RADIO_IP \
  --set mqtt.enabled true \
  --set mqtt.address MQTT_HOST:MQTT_PORT \
  --set mqtt.root bgmesh/EU_868 \
  --set mqtt.encryption_enabled false \
  --set mqtt.json_enabled false

meshtastic --host RADIO_IP \
  --ch-set uplink_enabled true --ch-index RNS_CHANNEL_INDEX \
  --ch-set downlink_enabled true --ch-index RNS_CHANNEL_INDEX
```

При TLS добавете `--set mqtt.tls_enabled true`. TLS endpoint-ът на конкретния
broker не е част от потвърдения тест по-горе. За credentials е по-безопасно да
ползвате официалния Meshtastic UI, вместо да оставяте паролата в shell history.

За този MVP `mqtt.encryption_enabled` трябва да е `false`: virtual node изпраща
decoded binary ServiceEnvelope, а физическият gateway го криптира с channel PSK
преди LoRa предаването. Използвайте отделен RNS channel, TLS и broker ACL. Самият
Reticulum packet остава криптографски защитен, но broker-ът вижда транспортните
метаданни.

### MQTT→LoRa routing probe

Преди пълен Reticulum/LXMF тест проверете downlink-а с един малък port-76
packet и задължителен Meshtastic routing ACK. Командата не изисква Reticulum
bridge на target устройството:

```bash
uv run rns-meshtastic mqtt-radio-probe \
  --host mqtt.meshtastic.vip \
  --port 1883 \
  --root msh/Bulgaria \
  --channel HQ \
  --channel-index 0 \
  --source '!ee00a111' \
  --destination '!a1b3b3b8' \
  --hops 3 \
  --username meshdev
```

Паролата се въвежда скрито. Очакваният край е:

```text
routing ACK: gateway=!........ source=!a1b3b3b8 destination=!ee00a111 error=NONE
MQTT radio probe passed
```

Това доказва broker→gateway→LoRa доставката и обратния LoRa→MQTT ACK. Не
доказва, че target устройството има Reticulum/LXMF bridge — за това е нужен
последващ RNCP или LXMF end-to-end тест.

На 11 август 2026 г. проверката премина през `mqtt.meshtastic.vip`, физически
gateway `!8fd13c64` и target T-LoRa Pager `!a1b3b3b8` по `HQ`. Предварителен
traceroute с изключен MQTT показа един relay напред. Probe-ът с `--hops 3`
получи `ROUTING_ERROR_NONE`, което потвърди, че този broker path не е ограничил
пакета до zero-hop.

### Broker-и без zero-hop policy

Няма hard-coded zero-hop предположение. Полето:

```ini
mqtt_downlink_hops = 0
```

задава hop limit в публикувания `MeshPacket`. За broker, който не го пренаписва,
можете контролирано да зададете `1`, `2` или максимум `3`.

Тествайте последователно:

1. `0` — target radio трябва да е директен RF съсед на MQTT gateway.
2. `1` — target трябва да е достижим през един relay.
3. `2`/`3` — само ако топологията действително го изисква.

При всяка стойност следете channel utilisation. Използвайте само един downlink
gateway в един и същ RF coverage island. Firmware маркира packet-а `via_mqtt`, а
virtual backend deduplicate-ва `(from, packet_id, payload digest)`, но това не
предотвратява едновременна LoRa трансмисия от няколко физически gateway-а.

## Диагностика

| Симптом | Най-вероятна причина |
|---|---|
| Няма PhoneAPI връзка | друг TCP client вече използва port 4403 |
| Текстовите съобщения работят, port 76 не | relay е `CORE_PORTNUMS_ONLY` |
| Fragment-и се виждат, но няма RNS frame | различен IFAC или липсващ fragment; увеличете `tx_interval` |
| MQTT uplink работи, downlink не | channel downlink е изключен или `mqtt.encryption_enabled=true` |
| MQTT достига само gateway съседа | broker налага zero-hop или `mqtt_downlink_hops=0` |
| Много дублирани MQTT packets | повече от един uplink gateway; dedup е очакван и необходим |
| Unicast дава PKI failure | gateway public key липсва/е сменен; върнете `pki_required=No` и проверете NodeDB |
