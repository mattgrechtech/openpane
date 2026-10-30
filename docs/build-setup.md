# Build set-up

What's needed to build the app without Android Studio or an emulator. The steps are for Windows; [macOS and Linux](#macos-and-linux) are the same tools with different commands. About 3–5 GB and half an hour. Test on a real TV over adb.

| Piece | Why | Size |
|---|---|---|
| JDK 17 (Eclipse Temurin) | Runs Gradle and the Kotlin compiler | ~300 MB |
| Android SDK command-line tools | `sdkmanager`, to fetch the rest | ~150 MB |
| SDK platform 35 + build tools 35 | Compile against Android 15's API | ~1–2 GB |
| adb (platform-tools) | Install on the TV (you may have it already) | ~15 MB |
| Gradle 8.11.1 | Downloaded automatically by the wrapper on first build | ~150 MB |
| Libraries | Downloaded by Gradle on first build | a few hundred MB |

## 1. JDK 17

```powershell
winget install EclipseAdoptium.Temurin.17.JDK
```

Close and reopen the terminal, then check:

```powershell
java -version
```

It should say `17.x`. If `JAVA_HOME` isn't set (Gradle complains), set it to the JDK folder (e.g. `C:\Program Files\Eclipse Adoptium\jdk-17...`) in System → Environment variables.

## 2. Android SDK command-line tools

1. Download **"Command line tools only"** for Windows from <https://developer.android.com/studio#command-line-tools-only> (accept the licence on the page).
2. Create `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\` and unzip so that `...\latest\bin\sdkmanager.bat` exists (the zip has a top-level `cmdline-tools` folder: move its contents up into `latest`).
3. Set environment variables (System → Environment variables, user scope):
   - `ANDROID_HOME` = `%LOCALAPPDATA%\Android\Sdk`
   - add `%ANDROID_HOME%\cmdline-tools\latest\bin` and `%ANDROID_HOME%\platform-tools` to `Path`
4. New terminal. Recent command-line tools replace `sdkmanager` with the `android` CLI; installing a package accepts the Android SDK licence (<https://developer.android.com/studio/terms>):
   ```powershell
   android --sdk=$env:ANDROID_HOME sdk install "platforms;android-35" "build-tools;35.0.0" "platform-tools"
   ```

## 3. Gradle

Nothing to install: the repo includes the Gradle wrapper (`gradlew`, `gradlew.bat`), which downloads Gradle 8.11.1 on first use.

## 4. Build and test

```powershell
.\gradlew.bat test            # unit tests (settings, HTTP server, API auth, pop-ups, MQTT discovery, HA URLs)
.\gradlew.bat assembleDebug   # app\build\outputs\apk\debug\app-debug.apk
```

The first run downloads Gradle and the libraries and takes a few minutes; later builds take seconds.

## 5. Install on a TV

The TV needs developer options and network debugging on (the kit's [TV prep guide](https://github.com/mattgrechtech/ha-tv-kit/blob/main/docs/02-tv-prep.md) covers it). Then:

```powershell
.\tools\provision.ps1 -Tv 192.168.1.60 -StartUrl "http://192.168.1.50:8123/house?tv=1"
```

It installs the APK, grants "display over other apps", excludes the app from battery optimisation, sets the start URL, opens the dashboard and prints the API token.

**If you used ha-tv-kit's TV scripts:** they're separate and still apply. The kit's `tv/backups/<name>/` folders (from `backup` / `restore`) hold the TV's package list and settings from before debloating; the provisioning script doesn't read or change them, and `restore` doesn't remove this app. The overlay permission is granted the same way the kit grants it to TvOverlay (`appops set <package> SYSTEM_ALERT_WINDOW allow`). If TvOverlay is installed and you turn on this app's TvOverlay-compatible mode, disable TvOverlay on that TV first (both use port 5001); the kit's `restore` script re-enables it.

Logs while testing:

```powershell
adb -s 192.168.1.60:5555 logcat -s KioskActivity HaBridge KioskService MqttBridge PopupManager KioskController
```

## Troubleshooting

**`java.io.IOException: Unable to establish loopback connection`** (from `android`, Gradle or any Java tool). Java opens an AF_UNIX socket in the temp folder for its internal pipes, and on some Windows set-ups that fails for folders under `AppData\Local` ("Invalid argument: connect"). Point Java at a folder where it works, for the current terminal:

```powershell
New-Item -ItemType Directory -Force "$env:USERPROFILE\.jdk-sockets" | Out-Null
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$env:USERPROFILE\.jdk-sockets"
```

To make it permanent, set the same `JAVA_TOOL_OPTIONS` as a user environment variable. Java then prints "Picked up JAVA_TOOL_OPTIONS" once per run, which is harmless.

## macOS and Linux

- JDK 17: `brew install --cask temurin@17` (macOS) or your distribution's `openjdk-17-jdk`.
- Android command-line tools: the same download for your platform, unzipped to `~/Android/Sdk/cmdline-tools/latest`; set `ANDROID_HOME=~/Android/Sdk` and add its `cmdline-tools/latest/bin` and `platform-tools` to `PATH`.
- Then `./gradlew test assembleDebug`, and `./tools/provision.sh 192.168.1.60 "http://192.168.1.50:8123/"` to install.

## Release builds

Release builds are signed. Create a key once and keep it **outside the repo**, in a password manager plus a backup: losing it means everyone has to uninstall to update.

```powershell
keytool -genkeypair -v -keystore $HOME\keys\openpane-release.jks -alias release -keyalg RSA -keysize 4096 -validity 10000
```

Then either put a `keystore.properties` file in the repo folder (it's git-ignored):

```properties
storeFile=C:/Users/you/keys/openpane-release.jks
storePassword=...
keyAlias=release
keyPassword=...
```

or set the environment variables `TVKIOSK_KEYSTORE`, `TVKIOSK_KEYSTORE_PASSWORD`, `TVKIOSK_KEY_ALIAS` and `TVKIOSK_KEY_PASSWORD`. `.\gradlew.bat assembleRelease` then writes `app/build/outputs/apk/release/app-release.apk`. Without a key it writes an unsigned APK, which Android won't install.

GitHub builds a signed APK for every version tag (`v0.1.0`) when the repository has these secrets: `KEYSTORE_BASE64` (the .jks file, base64), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. See `.github/workflows/release.yml`, and [releasing.md](releasing.md) for the whole release checklist.

Version numbers: `versionName` in `app/build.gradle.kts` is the only one to change; `versionCode` is worked out from it (1.2.3 → 10203), so every release installs over the last.
