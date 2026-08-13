# Android bridge

This app exposes a Reticulum-compatible TCP interface on
`127.0.0.1:7822` and carries its frames over Meshtastic port 76. It talks
directly to a radio through TCP PhoneAPI or the Meshtastic BLE GATT profile;
the Meshtastic Android app is not required.

The bridge is intentionally loopback-only. Sideband or Columba runs on the
same phone and connects to it as a normal Reticulum TCP client.

## Reproducible build

No Android SDK or Gradle installation is required on the host:

```bash
cd android
docker compose build android-build
docker compose run --rm android-build
```

The APK is written to:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

Compose keeps Gradle's debug signing keystore in the
`android-debug-signing` Docker volume. Keep that volume to install later debug
builds as upgrades. Removing the volume changes the signing identity and then
Android requires the previously installed debug app to be uninstalled first.

Install it with an existing `adb`, or copy the APK to the phone and approve
installation from that source. The Docker image pins Gradle/AGP, Android API
35, build-tools 35.0.0, and verifies the official Android command-line-tools
archive by SHA-256.

## Radio transports

- **TCP:** enter the Wi-Fi radio address and port `4403`. Do not keep another
  PhoneAPI TCP client connected to that radio.
- **BLE:** enter the radio MAC address. Disconnect it from the official
  Meshtastic app first; a BLE peripheral cannot serve both apps at once.

The app requests only the runtime permissions required by the selected
transport. It runs as a foreground `connectedDevice` service. It deliberately
does not hold a permanent partial wake lock; Android's BLE stack and the
foreground service keep the connection lifecycle active without forcing the
CPU awake continuously.

See `../docs/ANDROID_TESTING.md` for the complete Linux gateway, Sideband and
hardware test sequence.
