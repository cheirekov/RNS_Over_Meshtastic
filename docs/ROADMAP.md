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

### Field acceptance status

На 13 август 2026 connectivity частта на Phase A премина с два Android 0.1.8
bridge-а в reciprocal fixed-unicast режим и изключен Meshtastic radio ACK, но
reliability acceptance още **не е преминат**:

- двупосочни LXMF съобщения и завършени RNS frames;
- входящият transport metadata отчете `LoRa`, PKI unicast, 1–2 RF hops, SNR и
  RSSI и в двете посоки;
- няма bridge queue drops;
- единият край приложи TCP backpressure при натрупана radio queue, без загуба
  в bounded scheduler-а;
- някои изпращачи показаха липсващ LXMF delivery proof, въпреки че
  получателят имаше съобщението. Това потвърждава нуждата radio ACK, Reticulum
  proof и LXMF status да остават отделни измервания;
- други кратки съобщения действително не пристигнаха, а малък image transfer
  от BLE страната не завърши, докато изпращане от TCP PhoneAPI страната успя.
  Следователно резултатът доказва пътя, но не приемлива delivery reliability.

Кодовият преглед откри асиметричен риск: TCP `send()` завършва след синхронен
socket write, докато BLE 0.1.8 връща успех след поставяне в локалната GATT queue,
преди `onCharacteristicWrite` да потвърди операцията. Късен GATT failure или
липсващ callback може да остави scheduler-а с неправилно отчетен изпратен
fragment. Android 0.1.9 изчаква callback-а, прекъсва неопределена GATT сесия при
15-секунден timeout и прави най-много два локални retry опита. `local retries`
и окончателният `dropped` резултат вече са видими и трябва да бъдат измерени
отделно от RF loss.

Има и независимо ограничение в legacy two-byte fragmentation формата. Receiver
научава общия брой fragments едва когато получи fragment-а с отрицателна
позиция (последния). Ако точно той се изгуби, receiver няма достатъчно данни да
поиска липсващата позиция. Ако последният е получен, но `REQ` repair пакетът се
изгуби, 0.1.9 няма периодичен repair scan и assembly може да изтече. Това е
следващият protocol-reliability подетап; първо ACK off/on измерването трябва да
покаже колко от загубата Meshtastic unicast retry вече покрива без промяна на
съвместимия wire format.

### Phase B result: ACK-all is rejected

Phase B с Android 0.1.8 и `want_ack` върху всеки unicast port 76 fragment
показа точна корелация, но неприемливо поведение:

- TCP страна: 69 TX fragments = 7 confirmed + 39 timed out/unknown + 23 pending;
- BLE страна: 76 TX fragments = 22 confirmed + 30 timed out/unknown + 24 pending;
- няма explicit NAK, но след първото бързо съобщение delivery latency нараства
  и част от следващите съобщения не пристигат.

Това отхвърля `ACK all fragments` като default reliability policy. Всеки кратък
LXMF обмен създава няколко RNS frames/fragments; ACK и firmware retry за всеки
от тях заемат обратния път, конкурират delivery proofs и могат да поддържат
radio-то в продължителна опашка дори когато bridge queue вече е празна.

Следващата ACK политика за оценка е `critical`, не `all`: ACK само за последния
data fragment на многофрагментен RNS frame (той носи общия fragment count) и за
редките repair request/retransmit control packets. Еднофрагментните RNS frames
остават на Reticulum proof/retry логиката, а обикновените междинни fragments се
възстановяват с fragment repair. Политиките
трябва да станат явни `off / critical / all`, като `all` остане само
диагностичен stress режим.

### След Phase B: producer/radio rate mismatch

Уточнението, че същият модел се наблюдава и при изключен radio ACK, показва,
че ACK-all усилва, но не причинява първичния проблем. Изходът е характерен за
producer/consumer backlog: първите един-два пакета стигат при празна опашка,
а следващите чакат или се губят след като двата Reticulum края започнат да
генерират data, proofs и повторения едновременно.

Проверката на използвания RNS 1.4.2 показа, че стандартният Android-facing
`TCPInterface` задава твърдо `10_000_000` bps. Стойността не е само UI
метаданна. За MTU 500 Reticulum изчислява first-hop времето като
`6 s + MTU*8/bitrate`: около 6.0004 s при TCP оценката, вместо 11 s при
приблизително 800 bps scheduler rate. С един известен hop началният receipt и
link-establishment timeout са около 12 s срещу 17 s, преди да се отчете
натрупана опашка и обратният proof трафик.

Android 0.1.11 намалява radio queue horizon от 120 s на 8 s. При default
`2000 ms` pacing има четири fragment слота, един от които е резервиран за
repair control, тоест data частта побира един максимален триfragmentен RNS
frame. Малък TCP receive buffer позволява backpressure да достигне Sideband или
Columba значително по-рано. Това е съвместима transport корекция, а не промяна
на port 76 framing-а. Тя трябва да се тества първо с ACK `off`; `critical` е
отделният следващ експеримент.

EU_868 duty cycle е отделна контролирана променлива. Firmware прилага 10% TX
airtime върху rolling 60-minute прозорец и връща local routing error
`DUTY_CYCLE_LIMIT (9)`, когато прекрати send. След многократни полеви серии това
може да изглежда точно като „първите пакети минават, останалите спират“.
Android 0.1.11 пази брояч и последния non-zero device queue result, за да не се
обърка регулаторното прекъсване с LoRa загуба. `override_duty_cycle` остава
изключено.

Полевото измерване след три кратки LXMF съобщения показа последователно около
7–8%, 20–30% и 41% `ChUtil`. Това е кратката channel-occupancy метрика, не
`air_util_tx`, но доказва congestion feedback. Текущият firmware използва 25%
за polite/background gate и 40% за максималния channel-utilization gate, а
contention delay нараства с натоварването. Следващият acceptance run започва
при idle ChUtil под 10%, паузира при 25% и спира при 40%, за да не превръща
диагностиката в допълнителна причина за загуби.

### Android 0.1.11 field result: local flow control works, crossing traffic does not

Първият двустранен run с 0.1.11 и ACK `off` потвърди локалната корекция, но не
и end-to-end reliability:

- BLE край: 54 RNS frames / 87 fragments TX, 30 / 51 RX, 25 backpressure
  събития, 0 local retries, 0 dropped и 0 device rejects;
- TCP PhoneAPI край: 48 / 77 TX, 23 / 46 RX, 9 backpressure събития,
  0 local retries, 0 dropped и 0 device rejects;
- и двете bridge queues и firmware TX queues се върнаха до празни;
- бавно редувани съобщения пристигат значително по-надеждно, но когато двата
  края натрупат насрещни серии, част от съобщенията/доказателствата закъсняват
  или не пристигат;
- последният входящ packet на BLE края беше маркиран MQTT, а на TCP края LoRa,
  следователно този run е mixed LoRa/MQTT, не контролиран pure-LoRa резултат.

Това отхвърля BLE GATT и локалната Android queue като първична причина.
Празната queue не означава, че непълен frame може да се възстанови: legacy
receiver без получен final fragment не знае общия fragment count, а при изгубен
еднократен `REQ` няма periodic repair scan. Допълнително двата независими
bridge scheduler-а нямат общ half-duplex turn-taking и могат да пресекат data,
proof и repair traffic.

Следващият ограничен тест е 0.1.11 `critical`, първо върху чист път и в три
отделни серии: one-way A→B, one-way B→A и едва след това crossing A↔B. Ако
critical защити final fragment, но crossing серията остане слаба, следващата
wire-compatible разработка е periodic repair scan. След нея трябва да се
оцени negotiated frame-level stop-and-wait/window=1 за fixed unicast, с
детерминистично turn-taking; това е по-професионално от произволно увеличаване
на delay или ACK върху всеки fragment.

### Android 0.1.11 critical acceptance: preliminary short-message pass

Контролираният `critical` run премина без изгубено LXMF съобщение и в трите
серии: one-way A→B, one-way B→A и crossing A↔B. Наблюдаваното крайно състояние:

- BLE край: 62/102 TX frames/fragments, 36/71 RX, 24 backpressure, 0 local
  retries, 0 dropped и 0 device rejects; radio ACK 23 confirmed, 13 unknown и
  4 още pending в момента на снимката;
- TCP PhoneAPI край: 43/83 TX, 28/56 RX, 6 backpressure, 0 local retries,
  0 dropped и 0 device rejects; radio ACK 19 confirmed, 12 unknown и 9 pending;
- и двата последни входящи port 76 packets са отчетени като LoRa/PKI, но това
  само по себе си не доказва, че целият run е бил без MQTT-assisted packets;
- LXMF UI още може временно да показва липсващ proof (`!`) след като
  отсрещното съобщение вече е видимо. Това остава delivery-status latency, не
  загубено съобщение.

`ChUtil` достигна 47% след последното вече видимо съобщение. Това не означава,
че bridge queue лъже. Firmware метриката е rolling 60-second сбор на локален TX
и целия чут RX airtime, включително валидни mesh packets, чужд LoRa трафик и
недекодиран/noise airtime. В момента на снимките още имаше 4/9 radio ACK
pending, а единият firmware queue показваше 14/16 free, тоест два пакета още
чакаха. Reticulum/LXMF proof, Meshtastic routing ACK, repair/control или външен
mesh трафик могат да продължат след визуалната доставка на текста. Announce е
възможен, но не може да бъде заключен само от ChUtil.

Този първоначален acceptance е положителен за кратки съобщения при
дисциплинирано pacing, но не доказва burst/file reliability. Преди нов wire
protocol следват повторяем soak и измерване на post-delivery tail: counters при
визуална доставка, след 30 s, 60 s и 90 s.

#### Follow-up tail run: acceptance reopened

Последващият двупосочен `Tail1` run възпроизведе реална загуба, въпреки
празните bridge и firmware queues:

- TCP PhoneAPI краят при изпращане на `Tail1` нарасна от 43/83 до 49/92
  TX frames/fragments; локалният TX приключи, но LXMF съобщението не се появи
  в отсрещния клиент;
- BLE краят при обратното `Tail1` нарасна от 63/106 до 69/114 TX; локалният TX
  също приключи, но съобщението не се появи в отсрещния клиент;
- и в двете посоки има 0 `rejects`, 0 local retries и 0 `dropped`; scheduler
  queue се връща до нула, а firmware queue до 16/16 free;
- `critical` ACK отчита потвърдени и оставащи `unknown/pending` fragments, но това
  не е frame-level или LXMF receipt и не може да докаже завършеното сглобяване.

След двете загуби е изпратено по едно `Tail2` и в двете посоки, което пристига
почти веднага. Това е съвместимо със „студен“/изтекъл Reticulum path/link, който
първият опит загрява, или с незавършено fragment assembly. Само от тези броячи
двете хипотези не могат да се разделят. Затова reliability acceptance остава отворен;
`critical` остава най-добрият текущ кандидат, но не и доказан default.

#### Same-room laboratory run: text path passes, resource admission fails

На 14 август 2026 два Meshtastic radio възела в една стая, единият през BLE и
другият през TCP PhoneAPI, обмениха кратки LXMF съобщения почти веднага както в
unicast, така и в channel broadcast режим. В broadcast кадрите входящият път е
директен LoRa, channel-encrypted, channel slot 1, 0 hops, приблизително
SNR 9–13 dB и RSSI -44 до -72 dBm. Това е силен контролиран резултат, че BLE,
TCP PhoneAPI, port 76 framing и кратките RNS frames работят при чист RF път.

Двата опита за малки изображения/resource messages не завършиха. Кадрите
локализират поне една детерминистична причина преди RF слоя:

- на TCP bridge-а `dropped` нараства последователно от 1 до 4, докато
  `local retries = 0`, `device rejects = 0` и radio queue се връща до нула;
- това означава scheduler admission rejection, а не BLE/GATT failure,
  Meshtastic duty-cycle rejection или доказана RF загуба;
- Sideband показва `100% done` и променящи се 2.88 Kbps, 656/479/190/19 bps,
  преди крайното `Failed`. Тези стойности описват локалния Reticulum resource
  progress/estimate, а не потвърдените bytes през Meshtastic или получателя;
- текущ Sideband създава `BackboneClientInterface` с high-speed bitrate guess.
  Reticulum autoconfigure може да избере hardware MTU от много KiB, докато
  Android 0.1.11 допуска само три data fragments (около 600 bytes при body 200)
  плюс отделен repair reserve. Затова голям RNS resource frame не може изобщо
  да бъде приет в кратката LoRa queue.

Android 0.1.12 добавя точния размер/причина за admission rejection и отделни
reassembly/repair counters, без промяна на port 76 wire format или scheduling.
Не увеличаваме сляпо queue horizon до минути: това би скрило MTU/bitrate
несъответствието, би създало Reticulum timeout/retry дубликати и би натоварило
LoRa канала. File/resource transport остава отделен архитектурен подетап.

Android 0.1.13 премахва загубата при кратък local-client restart: завършен
входящ RNS frame чака до пет минути във volatile FIFO с лимит 32 frames/64 KiB
и се replay-ва по ред при reconnect. Duplicate, expiry и reject събитията са
видими отделно. Това не е persistent store-and-forward; offline mailbox и
дългосрочно съхранение остават работа на LXMF/lxmd.

### Междуградски field резултат и край на permutation тестовете

- При MQTT на `tarnovo-gorna` и `gorna2` кратките съобщения пристигат почти
  веднага и в двете посоки.
- При изключен MQTT само на `tarnovo-gorna` пакетите пак пристигат през близък
  публичен gateway, но с по-голяма и асиметрична латентност; това измерва
  реалния LoRa→gateway→MQTT сегмент, а не чист end-to-end LoRa.
- Fragment repair успява да довърши frames при загуба; няма отчетен scheduler
  reject, device reject или `DUTY_CYCLE_LIMIT` за кратките съобщения.
- LXMF `Failed` при вече получено съобщение означава, че delivery proof не се е
  върнал в timeout-а на клиента. Не доказва загуба на payload-а.
- `via_mqtt=true` заедно с LoRa hop/RF metadata означава MQTT произход и финален
  LoRa ingress. От 0.1.13 това се показва като `MQTT→LoRa`, а не само `MQTT`.

Следва един комбиниран reconnect acceptance тест и после дълъг soak. Не са
необходими още ръчни комбинации на същите MQTT настройки.

### Android 0.1.13 reconnect, 0.1.14 repair и 0.1.15 asymmetric-path hardening

Reconnect тестът отдели двата слоя ясно:

- local spool-ът отчете `replayed 10 buffered frame(s)`, общо 15 replayed
  frames, `expired: 0` и `rejected: 0`; следователно FIFO replay към локалния
  Reticulum client работи;
- въпреки това конкретно LXMF съобщение не се появи. Radio reassembly
  едновременно показа 9 expired assemblies на единия край и `3 active,
  3 awaiting final` на другия;
- при активиран MQTT две от три кратки съобщения пристигнаха. Няма scheduler,
  PhoneAPI или spool reject, който да обясни третото.

Това доказва реален failure mode преди spool-а и е най-силното обяснение за
липсващия текст: без final fragment port 76 receiver-ът не знае общия брой
позиции, не създава завършен RNS frame и няма какво да replay-не към
Sideband/Columba. Понеже transport-ът вижда opaque RNS frames, снимките сами по
себе си не могат криптографски да свържат конкретния LXMF текст с конкретно
assembly. Android 0.1.14 и актуалният Linux код добавят periodic scan на
stalled assemblies. `REQ` с позиция `0` означава „върни cached final fragment“;
след получаването му съществуващата missing-position repair логика довършва
frame-а. Data fragment с позиция zero остава невалиден, така че wire форматът
не се променя. Стар peer игнорира разширението; за реално възстановяване и
sender, и receiver трябва да са 0.1.14/актуалния Linux код.

Трите контролирани серии на 14 август са проведени само в `broadcast` режим и
отделиха radio-path проблема от bridge поведението, но не сравняват broadcast
с fixed unicast:

- с MQTT на крайното Wi-Fi radio двупосочните кратки съобщения минаха чисто;
- без неговия MQTT едната посока продължи да работи, а обратната почти изчезна;
- след изключване на локалния gateway остана същата асиметрия. Това съвпада с
  независимо наблюдаван еднопосочен слаб Meshtastic път и не е RNS routing
  дефект, но fixed-unicast поведението трябва да се измери отделно;
- 0.1.14 обаче усили отказа: един bridge стигна 48–56 periodic `REQ`, а другият
  натрупа десетки `MAX_RETRANSMIT`, control-queue rejects и channel utilization
  до около 48%. Макар data frames да са broadcast, repair request/retransmit
  пакетите са unicast и при 0.1.14 `critical` искаше radio ACK за тях.
  Следователно неограниченият петсекунден repair loop е отхвърлен.

Android 0.1.15 и Linux кодът ограничават всяка липсваща позиция до три periodic
опита с 5/10/20-секунден backoff и планират най-много един repair на scheduler
pass; Android показва и текущия `capped` брой. В broadcast режим repair control
остава unicast, но без Meshtastic radio ACK; Reticulum/LXMF proofs остават
авторитетни. Целта не е да се скрие прекъснат Meshtastic маршрут, а той да се
преживее без repair storm и без starvation на новите кратки frames.

### Android 0.1.15 bounded-repair field result

Следващият двурежимен тест е проведен с изключен MQTT broker client на
крайните radios, първо в broadcast и после в reciprocal fixed-unicast:

- broadcast `ab1` и `bb1` са получени за приблизително 3 и 6 секунди и са
  получили LXMF delivery status;
- fixed-unicast `au1` и `bu1` са получени за приблизително 4 и 11 секунди, но
  изпращачите по-късно са показали `Failed`, тоест payload delivery е успяло,
  а обратният LXMF proof не е наблюдаван в клиентския timeout;
- и в двата режима няма scheduler/device reject, local drop, expired assembly
  или `capped`; всички наблюдавани assemblies са завършили. Не се повтаря
  0.1.14 repair/ACK storm, така че bounded-repair regression преминава за този
  реален път;
- това не е pure-LoRa тест. Bridge status показва `OK-to-MQTT permission: true`,
  `MQTT` на Wi-Fi radio и `MQTT→LoRa` на pager-а. Изключването на локалния MQTT
  broker client не изчиства `Data.bitfield` разрешението друг Meshtastic gateway
  да uplink-не пакета. Следователно поне част от port 76 data/repair трафика е
  използвала публична MQTT инфраструктура.

За доказуем pure-LoRa regression 0.1.17 добавя transport настройка, която може
безопасно да форсира `OK-to-MQTT=false` за bridge packet-ите, без да включва
разрешението против radio policy. API е `inherit / force_off`; `force_on`
умишлено не съществува.

### Android 0.1.15 storm result и 0.1.16 containment

Контролираната буря до приблизително 50% ChUtil показа, че per-position лимитът
сам по себе си не е глобален лимит: единият край отчете 68 repair REQ, другият
58 retransmits, 27/46 backpressure събития и съответно 2/6 admission rejects.
При втория край radio ACK return path беше практически неизползваем — 0
confirmed и 92 unknown — докато другата посока имаше 59 confirmed. Това е
асиметрия на transport/control path, не доказателство за 92 недоставени LXMF
съобщения. Празната крайна queue също не отменя факта, че control бурята вече е
изяла airtime.

0.1.16 въвежда един общ rolling budget от 12 repair requests за 60 секунди за
всички assemblies, без промяна на port 76 wire format. Repair, който не може да
влезе в control queue, се отлага без да изразходва per-position опит. Scheduler
редува чакащ control с data, а telemetry разделя data reject/failure от control
reject/failure. Поправен е и reassembly edge case, при който capped missing
fragment можеше да доведе до опит за сглобяване на непълен frame.

Новият optional `adaptive` ACK режим използва sparse `critical` selection, но
го спира за пет минути при 12 pending без нито едно потвърждение или при поне 8
resolved резултата с под 25% confirmations. Това намалява ACK amplification по
доказано лош обратен път, без да превръща radio ACK в LXMF delivery verdict.
Timestamp/version diagnostics вече се копират директно от Android UI.

Първият 0.1.16 reciprocal-unicast run достави и четирите кратки payload-а без
data/control admission failure, device reject или local retry. Две LXMF
съобщения получиха proof, а две пристигнаха без proof в наблюдавания прозорец.
ACK пътят остана силно асиметричен: 13 confirmed на единия край срещу 0
confirmed, 3 unknown и 13 pending на другия. Тестът е бил с `critical`, не с
`adaptive`, и не е pure-LoRa: статусът показва `MQTT` и `MQTT→LoRa`, въпреки
спрените broker clients на крайните radios. Това окончателно оправдава
per-packet `force_off`, вместо още двусмислени MQTT-off тестове.

0.1.17 реализира `inherit / force_off` както за Android, така и за Linux native
PhoneAPI. Диагностиката показва configured/effective policy и запазва peak
bridge queue и peak device queue occupancy до рестарт, така че празната крайна
queue вече не заличава high-water mark от теста.

Следващият broadcast report с 0.1.16 е чист от queue pressure и repair storm,
но показва 2/3 и 5/8 RNS frames/fragments на двата края. Контролната проверка
на оператора установява, че ChUtil пада до 0 след спиране на bridge-а. Затова за
този setup трафикът не се приписва на ambient mesh: bridge-ът действително
предава фонови Reticulum рамки, макар cumulative counters да не показват вида
им. 0.1.18 добавя frame-type breakdown (data/announce/link/proof), последен RNS
context и rolling 60-second data/repair fragment/byte прозорец. Това е само
observability; wire protocol, pacing и repair policy не се променят.

### 0.1.18 same-room pure-LoRa acceptance

Два Android bridge-а с `force_off`, broadcast, channel slot 1 и hop limit 0 са
тествани в една стая. И двете посоки са отчетени като директен
channel-encrypted LoRa път с 0 hops, без MQTT/MQTT→LoRa:

- Honor TX 11 RNS frames/17 fragments съвпада точно с Pixel RX 11/17;
- Pixel TX 15 RNS frames/24 fragments съвпада точно с Honor RX 15/24;
- frame mix и byte totals също съвпадат точно в двете посоки: съответно
  `5 data + 2 announce + 4 proof = 1937 B` и
  `4 data + 7 announce + 4 proof = 2756 B`;
- няма repair request, retransmit, incomplete assembly, duplicate, scheduler
  reject, device reject, local retry или drop;
- Android inbound spool е replay-нал две рамки след кратко прекъсване на
  локалния Reticulum client, без radio retransmission или загуба.

Това приема BLE/TCP PhoneAPI, port 76 framing/reassembly, `force_off` и
broadcast reliability при идеален RF път. То едновременно потвърждава, че
наблюдаваният ChUtil е реален bridge-carried RNS трафик: run-ът съдържа девет
announce и осем proof рамки, общо 41 LoRa fragments. Honor firmware queue е
достигнала peak 11/16 used без reject; това е сигнал за по-ранна soft
backpressure, не доказана загуба.

0.1.19 опита packet-type priority и отделно 15-секундно announce shaping зад
обратим profile. Same-room pure-LoRa тестът доказа регресия: от 13 announce
рамки на Pixel до момента на диагностиката Honor беше получил само 5, докато
по-късни data рамки вече ги бяха изпреварили; не се появи нито един proof и
едната посока не достави краткия текст. Следователно суровият RNS TCP поток
има причинен ред, който прозрачният bridge няма достатъчно семантичен контекст
да пренарежда. 0.1.19 е оттеглена като тестова версия.

0.1.20 възстановява строг FIFO ред за всички RNS packet types и премахва
независимото announce spacing. `constrained_auto` запазва само безопасната
QueueStatus soft pacing: +1x/+2x/+3x от configured base interval при
25/50/75% occupancy, с максимум +8 секунди. `transparent` е FIFO с фиксирания
base interval. И двата режима запазват RNS wire bytes, fragmentation, repair,
ACK semantics и regulatory duty-cycle настройките.

Повторният same-room тест с 0.1.20 потвърждава FIFO възстановяването и payload
delivery, но не затваря delivery-proof acceptance. При crossing burst от пет
бързи съобщения във всяка посока payload-ите са пристигнали. В посока A→B
първите четири са получили delivery status приблизително до пристигането на
четвъртото, докато съобщенията B→A са пристигнали без нито един наблюдаван
proof при изпращача. Това е асиметричен обратен proof path под едновременен
broadcast товар, а не доказана payload загуба. Предоставените diagnostics са
копирани след нова/празна bridge сесия (`Reticulum listening`, всички counters
0), затова не могат да разграничат закъснял от изгубен proof. 0.1.21
`auto_single_peer` е следващият контролиран експеримент именно защото изпраща
data и proof с PKI unicast, без да променя FIFO реда.

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

Android 0.1.21 реализира първата bounded версия. Един изрично конфигуриран
Meshtastic Node ID е единственият допустим peer. RNS announce рамките се
изпращат по channel broadcast, а data/link/proof рамките — чрез Meshtastic
unicast към peer-а. Всички рамки остават в първоначалния FIFO ред. Входящият
port 76 филтър допуска от този peer само broadcast или пакети до локалното
radio. Няма learned peers, broadcast resend или промяна на RNS/LXMF bytes.

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
