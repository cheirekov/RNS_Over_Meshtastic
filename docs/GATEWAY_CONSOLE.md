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

Отворете `http://127.0.0.1:8787/`. UI показва отделно LoRa, LAN и public TCP
traffic, radio queue, Meshtastic peers, upstream status и LXMD propagation
hash. LXMD store usage е отделна метрика; Console не представя store bytes като
доказан propagation network traffic.

## Read-only API и metrics

- `GET /api/v1/capabilities`
- `GET /api/v1/status`
- `GET /api/v1/config` — само allowlisted полета; secrets са redacted
- `GET /api/v1/lxmd/qr`
- `GET /metrics`
- `GET /healthz`

HTTP отговорите не съдържат IFAC passphrases. Request body никога не се пише в
HTTP log. Prometheus endpoint-ът съдържа само counters и queue occupancy.

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

Независимо от профила bridge-ът никога не включва Meshtastic duty-cycle
override. При public upstream radio interface е `internal`, upstream-ът е
`boundary`, а `announces_from_internal = No`. `RNS_LAN_PUBLIC_VISIBILITY=yes`
показва public announces на доверени LAN/VPN клиенти, без да превръща radio-то
в public transit.

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
- `manual` — Console показва кандидати, операторът избира explicit upstream.
- `trusted_auto` — autoconnect само от identity hashes в
  `RNS_DISCOVERY_SOURCES`; връзките са `boundary` и не announce-ват към LoRa.

Discovery announce не е speed benchmark. Latency, uptime, reconnects и route
changes трябва да се наблюдават преди избор на upstream.
