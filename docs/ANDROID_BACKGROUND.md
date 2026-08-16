# Android background operation

Android bridge използва `connectedDevice` foreground service. Това е
дълготрайният Android режим за постоянна BLE или TCP връзка с външно
устройство. Service-ът:

- се стартира само от видимата Activity чрез **Save & start**;
- има постоянна notification с бутон **Stop**;
- остава отделен от Activity при смяна на приложение или изгасен екран;
- е `START_STICKY` и зарежда запазената конфигурация при позволено от Android
  process recreation;
- прави BLE/TCP reconnect с capped exponential backoff до 60 секунди;
- не държи безкраен partial wake lock;
- не натрупва heartbeat-и при изключено или недостъпно BLE radio;
- има bounded bridge TX queue, TCP backpressure и bounded BLE PhoneAPI queue;
- ограничава status/notification обновяванията, за да няма запис и UI wakeup
  при всеки отделен fragment;
- използва 30-секунден PhoneAPI heartbeat и никога не започва с firmware
  reserved nonce `1`, който би предизвикал ненужен NodeInfo LoRa broadcast.

Foreground service не може да заобиколи **Force stop**, Android Active Apps
**Stop**, отнети Bluetooth permissions или OEM task killer. След reboot
bridge-ът не се стартира сам — това е умишлено, за да има явно потребителско
действие и видима foreground notification.

## Pixel / стандартен Android

1. Разрешете `Nearby devices` и notifications.
2. От **Open background / battery settings** изберете Battery usage →
   `Unrestricted`, ако телефонът прекъсва bridge-а при изгасен екран.
3. Не натискайте **Force stop** и не използвайте системния **Stop** за active
   app, освен когато искате bridge-ът действително да бъде спрян.

## Honor MagicOS

Имената зависят от версията на MagicOS. Проверете и за bridge-а, и за
Sideband/Columba:

1. App info → Battery usage: разрешена background activity / unrestricted.
2. Settings → Battery → App launch: изключете автоматичното управление за
   приложението и разрешете `Auto-launch`, `Secondary launch` и
   `Run in background`, когато тези опции присъстват.
3. Разрешете постоянната notification.
4. При OEM версии с aggressively cleaned recent apps заключете приложенията в
   recent-apps екрана, ако тази функция съществува.

Това са OEM настройки и приложението не може надеждно или коректно да ги
включи вместо потребителя.

## Background soak test

Изпълнете го и на двата телефона преди файлови или store-and-forward тестове:

1. Стартирайте bridge и Reticulum клиента; уверете се, че `radio queue` е празна.
2. Изгасете екраните за поне 60 минути.
3. Изпратете кратко съобщение във всяка посока след 10, 30 и 60 минути.
4. Отдалечете едното BLE radio или го рестартирайте; очаквайте status с
   reconnect и автоматично възстановяване след връщането му.
5. Премахнете Activity от recent apps, но не натискайте Force stop. Bridge
   notification и обменът трябва да останат.
6. Запишете получени съобщения, LXMF delivery status, `backpressure`, `dropped`,
   максималната radio queue и приблизителния battery drain.
7. Копирайте diagnostics в началото и края, без `Save & start` между тях.
   `bridge session` трябва да е еднакъв, uptime да е нараснал, а неочакван
   transport/client restart да се вижда в `radio up/down` и
   `RNS client up/down`.
