# Working on this repo

An Android TV / Google TV kiosk app for Home Assistant. Kotlin, platform views (no AppCompat, no Compose), minSdk 29, targetSdk 35.

## Rules

- **Small and auditable.** Don't add a dependency without a reason written in the PR; prefer the platform. Current libraries: androidx.webkit, Paho mqttv3, ZXing core; TensorFlow Lite in the voice build only (`src/voice/`, the wake word). The standard build must never depend on it.
- **Security is a feature.** Never add: remote shell, script injection via the API, app install/uninstall, file access, cloud calls, analytics. New network-facing code needs the token and Host checks (see `api/ApiHandler.kt`). New permissions need a note in `SECURITY.md` and the README.
- **TV first.** Everything must work with a D-pad remote only. Don't rely on a Menu key (it's a shortcut, not the only way). Back must eventually lead to the TV home screen.
- **Android-free logic goes in plain Kotlin classes with unit tests** (`settings/`, `api/`, `mqtt/Discovery`, `overlay/Popup`, `web/HaUrls`). Keep Android calls at the edges.
- **Settings:** declare every setting once in `settings/Settings.kt` (`Keys`). Secrets have `secret = true`.
- **Commands:** add a command in `control/KioskController.execute`, then expose it in `api/ApiHandler.COMMANDS`, `mqtt/Discovery.toCommand` and `docs/api.md`.
- **No personal data** in code, docs or tests: placeholder IPs are 192.168.1.50 (HA) and 192.168.1.60 (TV); test hosts use `.test`.
- Match the surrounding style: short KDoc on classes and non-obvious functions, plain English, Australian/British spelling in user-facing text.

## Build and test

See `docs/build-setup.md`. `gradlew test` for unit tests, `gradlew assembleDebug` for the APK, `tools/provision.*` to install on a TV.
