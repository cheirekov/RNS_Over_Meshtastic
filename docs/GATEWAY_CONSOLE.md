# Linux Gateway Console

Gateway Console е непривилегирован sidecar за наблюдение и безопасно
подготвяне на конфигурации. Той не е част от `rnsd`, няма Docker socket и не
може да рестартира услугите от браузъра. По подразбиране се публикува само на
`127.0.0.1:8787`.

## Стартиране

```dotenv
RNS_CONSOLE_PUBLISH_IP=127.0.0.1
RNS_CONSOLE_PORT=8787
RNS_LORA_POLICY=conservative
RNS_PUBLIC_DISCOVERY=off
```

```bash
docker compose --env-file .env.linux-service -f compose.linux.yaml \
  up -d --build rnsd lxmd gateway-console
curl --fail http://127.0.0.1:8787/healthz
```

От отдалечен администратор използвайте VPN или SSH tunnel, вместо да
публикувате Console в интернет:

```bash
ssh -L 8787:127.0.0.1:8787 gateway-host
```

При публикуване на LAN/VPN адрес Console 0.5 отказва да стартира без Basic
authentication:

```dotenv
RNS_CONSOLE_PUBLISH_IP=172.16.19.10
RNS_CONSOLE_AUTH_MODE=basic
RNS_CONSOLE_USERNAME=operator
RNS_CONSOLE_PASSWORD=replace-with-at-least-16-random-characters
```

Basic auth не криптира връзката и затова остава зад VPN или HTTPS reverse
proxy. Само минималният `/healthz` е достъпен без credentials; UI, API и
Prometheus metrics изискват authentication. При loopback може да остане
`RNS_CONSOLE_AUTH_MODE=off`.

Отворете `http://127.0.0.1:8787/`. UI показва отделно LoRa, LAN, public TCP,
private IFAC TCP и LXMD propagation traffic, текущи скорости, общ radio queue pressure,
Meshtastic peers, LAN client count, upstream status и LXMD propagation hash.
Стойностите се форматират като B/KiB/MiB/GiB; tooltip, JSON API и Prometheus
запазват точните bytes. Ако LXMD не предоставя peer counters, UI показва
`not available`, а не подвеждащо `0 B`.

## Read-only API и metrics

- `GET /api/v1/capabilities`
- `GET /api/v1/status`
- `GET /api/v1/config` — само allowlisted полета; secrets са redacted
- `GET /api/v1/config/schema` — всички управлявани полета, типове, defaults,
  граници, препоръчителни стойности и contextual help
- `GET /api/v1/events?after=CURSOR&limit=100` — ограничен structured journal
- `GET /api/v1/lxmd/qr`
- `POST /api/v1/lxmd/announce` — operator-triggered propagation announce
- `GET /metrics`
- `GET /healthz`

HTTP отговорите не съдържат IFAC passphrases. Request body никога не се пише в
HTTP log. Prometheus endpoint-ът съдържа само counters, availability и queue
occupancy. Mutating endpoint-ите приемат само JSON, проверяват browser origin и
имат 64-KiB body limit. Console няма apply/restart права.

## Manual LXMD announce

`lxmd` се стартира през project-managed launcher, който използва официалния
LXMF router. Launcher-ът излага само `status` и `announce` през Unix socket с
mode `0600`; няма shell command, Docker socket или network control API.

Бутонът **Announce propagation node** показва confirmation, понеже announce-ът
умишлено консумира LoRa airtime. Cooldown-ът е 15 минути по подразбиране:

```dotenv
LXMD_MANUAL_ANNOUNCE_COOLDOWN_SECONDS=900
```

Минимумът е 300 секунди. Cooldown state се пази в LXMD volume, така че restart
не го заобикаля. Успешният отговор съдържа `announced_at` и
`next_allowed_at`; прекалено ранна заявка връща HTTP 429 и не announce-ва.

## Configuration schema и secrets

Формата се генерира от schema endpoint-а и има Basic/Advanced изглед за Radio,
Reticulum, LoRa safety, boundary upstreams, Discovery, IFAC, LAN/Console и LXMD.
Public и private upstream списъците имат Add/Remove редове. Private upstream-ите
използват отделен общ IFAC профил, който не може да съвпада с radio или LAN IFAC.
Secret полетата показват само `Configured`/`Not configured`; browser-ът не може
да ги променя. При staging текущите secret стойности се запазват от защитената
process environment, независимо че не присъстват в browser payload.

Формата е контекстна: например gateway Node ID се показва само за fixed-unicast
client, discovery tuning — само когато discovery е включено, а Basic-auth
полетата — само при избран `basic` режим. `auto_multi_peer` фиксира Linux role
на `hub`, а `broadcast` — на `client`. Панелът `Effective configuration`
обобщава реалната топология, boundaries, discovery и LoRa policy преди
Validate/Stage, за да не се разчита на скрити defaults.

## Events и alerts

Console пази най-много 512 structured събития и 512 KiB. UI polling-ът е на пет
секунди и има source/severity филтри. Announce observations са само в общия
event journal, а не в дублиран отделен панел. Journal-ът съдържа само operational metadata за radio reconnect,
peer/upstream transition, header-only LoRa announce observation, TX rejection,
repair expiry, LXMD announce и config
stage — никога RNS/LXMF payload, password или IFAC passphrase.

Alerts са текущи или delta-базирани: stale/missing telemetry, reconnect churn,
TX rejection/send failure, repair expiry/capping, stalled reassembly, upstream
down/flapping, LXMD unavailable/storage pressure и collector failure. Исторически
cumulative counter сам по себе си не оставя alert активен. Нормалното състояние
се показва като зелено `No active alerts`.

Meshtastic interface записва локален telemetry heartbeat на 10 секунди дори при
напълно тих ефир. Този heartbeat е само atomic JSON write и **не** предизвиква
LoRa/MQTT packet. Console различава `Radio transport: up` от
`Radio activity: idle`; stale alert означава спрял collector/interface, а не
липса на радио пакети.

## Validate, stage, apply и rollback

Browser-ът може да валидира и да създаде номериран stage, но не изпълнява
restart. След `Stage configuration` UI показва две команди:

```bash
umask 077
docker compose --env-file .env.linux-service -f compose.linux.yaml run --rm \
  gateway-console rns-meshtastic gateway-export \
  --stage-file /data/rns/gateway-staged/gateway-TIMESTAMP.env > .env.pending

uv run rns-meshtastic gateway-apply \
  --stage-file .env.pending \
  --target .env.linux-service \
  --compose-file compose.linux.yaml
```

`gateway-apply` прави backup, рендира конфигурацията, стартира `rnsd`, `lxmd` и
Console и чака health endpoint. При неуспех връща предишния env и отново
стартира предишната конфигурация. Backup-ът остава с mode `0600`.

Самостоятелна проверка без UI:

```bash
uv run rns-meshtastic gateway-validate \
  --env-file .env.linux-service --json
```

## LoRa policy

| Профил | TX interval | Queue | Repair budget | Употреба |
| --- | ---: | ---: | ---: | --- |
| `conservative` | 2.0 s | 32 fragments | 12/60 s | default за shared/community RF |
| `balanced` | 1.0 s | 64 fragments | 18/60 s | контролирана private mesh |
| `custom` | explicit | explicit | explicit | лаборатория, с видимо предупреждение |

`conservative` и `balanced` са налагани profiles, а не подсказки: при избран
именуван profile Console заключва expert pacing/queue/repair полетата и
validator-ът заменя останали стари overrides с точните стойности от таблицата.
Само `custom` приема ръчно зададени стойности.

Reticulum има и по-ниско ниво announce/ingress/egress rate-control настройки.
Те умишлено не са изведени като общи Console knobs: семантиката им влияе върху
path discovery и изисква отделни regression тестове. Първата защита остава
тестваният bridge scheduler, bounded queue/repair budget, peer announce-idle
lease и стандартният Reticulum announce airtime cap.

Независимо от профила bridge-ът никога не включва Meshtastic duty-cycle
override. При public или private upstream radio interface е `internal`,
upstream-ът е `boundary`, а `announces_from_internal = No`.
`RNS_LAN_PUBLIC_VISIBILITY=yes` показва boundary announces на доверени LAN/VPN
клиенти, без да превръща radio-то в public/private transit. Private upstream-ът
добавя IFAC authentication, но остава boundary, а не unrestricted gateway.

Acceptance gate за public boundary е 30-минутна announce storm с baseline и
краен snapshot. Ако няма локален клиентски трафик, public announces не трябва
да увеличават LoRa TX counter-а:

```bash
docker compose --env-file .env.linux-service -f compose.linux.yaml \
  --profile tools run --rm traffic-baseline
# 30 минути наблюдение
docker compose --env-file .env.linux-service -f compose.linux.yaml \
  --profile tools run --rm traffic-report
```

## Discovery

- `off` — default; няма discovery.
- `manual` — Console показва специалните interface-discovery кандидати, но **никога** не ги свързва автоматично. Бутонът `Prepare as explicit boundary` само добавя endpoint-а във staged form; последващият CLI apply остава задължителен.
- `trusted_auto` — autoconnect само от identity hashes в
  `RNS_DISCOVERY_SOURCES`; връзките са `boundary` и не announce-ват към LoRa.

`RNS_DISCOVERY_REQUIRED_VALUE` управлява минималната discovery stamp стойност,
а `RNS_DISCOVERY_GRAVITY` — предпочитанието на auto-connected интерфейсите.
Explicit public/private upstream може да бъде отбелязан като bootstrap чрез
съответния `*_BOOTSTRAP_UPSTREAMS` subset. Bootstrap е разрешен само при
`trusted_auto`: Reticulum го затваря, когато достигне configured auto-connect
лимита, и го връща ако няма нито един active discovered boundary. Следователно
bootstrap е seed/failover, а не speed-test или постоянен втори upstream.

Discovery announce не е нормален RNS/LXMF announce и не е speed benchmark.
Кандидат се появява само когато отсрещният Reticulum transport публикува
специален interface-discovery record. Console показва source identity, тип,
endpoint, stamp, hops и last-heard, но никога remote `config_entry` или IFAC
key/passphrase. Latency, uptime, reconnects и route changes трябва да се
наблюдават преди избор на upstream.

### Безопасен workflow за първо включване

1. Добави проверен public `host:port` като **persistent** boundary и избери
   `RNS_PUBLIC_DISCOVERY=manual`. Това събира каталог, но не прави никакъв
   automatic connection. Празен каталог е нормален, ако upstream-ът не
   публикува interface-discovery records.
2. Провери `Source` identity на намерения кандидат извън самата discovery
   мрежа (оператор/публикувана инфраструктурна документация). Hostname или
   Reticulum destination hash не са заместител на тази identity.
3. Едва след това избери `trusted_auto`, въведи identity в
   `RNS_DISCOVERY_SOURCES`, настрой разумен `RNS_DISCOVERY_MAX` (default `1`)
   и маркирай проверения статичен endpoint като bootstrap seed. Validate →
   Stage → explicit CLI apply.

При `trusted_auto` Console показва колко discovered boundary интерфейса са
active и кои static endpoints са temporary bootstrap или persistent. Тя не
твърди, че кандидатът е „най-бърз“ или „най-близък“: Reticulum discovery не е
latency benchmark.

## Announce visibility

Console event journal показва само header metadata за announce-и, които реално
са приети от или подадени към Meshtastic интерфейса: destination hash, посока и radio peer.
Никога не се пазят payload или announce app-data. При radio IFAC заглавието е
криптирано и UI изрично показва `opaque_ifac`; Reticulum route table остава
авторитетният източник.

В `auto_multi_peer` Linux не може да изпрати LAN announce към бъдещ Android
peer. Android bridge трябва първо да изпрати announce/frame, за да бъде създаден
динамичният peer interface; след това се изпраща нов LAN announce. UI показва
отделно състоянието, когато още няма научен radio peer.

При спрян Android bridge наученият Meshtastic peer не може да бъде премахнат
веднага, защото радиото и маршрутът може още да са валидни. След
`RNS_PEER_ANNOUNCE_IDLE_SECONDS` UI показва `announce-idle`: Linux запазва peer-а
и реалния Reticulum трафик, но не хаби airtime за обикновени периодични
announces. Показва се и броят `announces saved`.
