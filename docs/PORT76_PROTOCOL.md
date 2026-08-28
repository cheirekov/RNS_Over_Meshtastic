# Meshtastic Port 76 framing — frozen legacy v1

Този документ фиксира wire формата, използван от Python/Linux и Android.
Meshtastic `PortNum` е официалният `RETICULUM_TUNNEL_APP` (`76`). Payload-ът на
всеки Meshtastic packet е един fragment; bridge-ът не променя RNS frame bytes.

## Data fragment

```text
offset  size  type  meaning
0       1     u8    frame index, modulo 256
1       1     i8    fragment position, 1..127; negative marks final
2       N     bytes exact slice of the Reticulum frame
```

Позициите започват от 1. Еднофрагментен frame има position `-1`. Position 0 и
`-128` са невалидни за data. Максимумът е 127 fragments, а текущият body limit
е 230 bytes. Production default е 200 bytes.

Frame identity по време на reassembly е `(Meshtastic source Node ID, index)`.
След final fragment receiver-ът знае общия брой и може да поиска липсваща
позиция. Завършените frames се deduplicate-ват в ограничен TTL cache.

## Repair extension

```text
ASCII "REQ" || u8 frame_index || i8 requested_position
```

Положителна позиция иска точно този fragment. Position `0` означава „върни
cached final fragment“ и решава случая, в който receiver-ът още не знае общия
брой. Sender cache-ът първо търси `(index, requesting peer)`, после broadcast
entry. Repair е best-effort, bounded и не е LXMF receipt.

## Addressing и encryption

- Broadcast използва Meshtastic channel encryption и destination `^all`.
- Unicast използва Meshtastic Node ID/PKI според firmware и node database.
- Channel slot не е част от port 76 payload.
- MQTT forwarding permission не е част от framing-а.
- Reticulum криптографията остава end-to-end; framing-ът не добавя собствено
  encryption или authentication.

## Frozen binary vector

Canonical fixture е [port76-v1.json](../tests/vectors/port76-v1.json). При body
8 и frame bytes `00..13` пакетите са:

```text
00010001020304050607
000208090a0b0c0d0e0f
00fd10111213
```

Repair за position 2 е `5245510002`, а repair за final е `5245510000`.
Python и Android unit tests проверяват fixture-а. Бъдеща Rust/iOS
имплементация трябва първо да премине същите vectors.

Legacy v1 няма version byte. Несъвместим нов framing не може да бъде добавен
неявно; той изисква отделен capability negotiation и test vectors.
