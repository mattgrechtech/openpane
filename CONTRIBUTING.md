# Contributing

Thanks for helping. Bug reports from TVs other than the one it was built on are the most useful thing right now.

## Tested a TV or tablet?

Please report it, even if everything worked: open an issue with the **Device report** template. Reports fill the [tested devices](docs/devices.md) list, which is how people find out whether TV Kiosk suits their hardware.

## Reporting a bug

Open an issue with the TV's make and model, the Android and WebView versions (Settings → Status and about), what you did and what happened. Attach the diagnostics file (Settings → Status and about → *Save diagnostics*, or `GET /api/v1/diagnostics`). It leaves out passwords and tokens, but read it before posting: it includes your TV's IP address and your start URL.

Security problems: please don't open a public issue; see [SECURITY.md](SECURITY.md).

## Changing the code

Build and test as in [docs/build-setup.md](docs/build-setup.md): `gradlew test lintDebug assembleDebug`, then try it on a TV with `tools/provision.*`.

The project's rules, also followed by AI coding assistants (see [CLAUDE.md](CLAUDE.md)):

- **Small and auditable.** No new dependency without a reason in the pull request; prefer the platform.
- **Security is a feature.** No remote shell, script injection through the API, app install/uninstall, file access, cloud calls or analytics. New network-facing code needs the token and Host checks in `api/ApiHandler.kt`.
- **TV first.** Everything must work with a D-pad remote alone, and Back must always lead somewhere.
- **Plain Kotlin where possible,** with unit tests, and Android calls at the edges.
- **Settings** are declared once in `settings/Settings.kt`; **commands** in `control/KioskController`, then exposed in the API, MQTT and `docs/api.md`.
- **No personal data** in code, docs or tests: use `192.168.1.50` (Home Assistant), `192.168.1.60` (a TV) and `.test` hosts.
- Australian/British spelling and plain English in anything users read.

## Maintenance

- **Issues get a first reply within about a week.** Bugs that break the app for people come first.
- **If the project ever stops,** the README will say so plainly, and the repository will be handed to a willing maintainer or archived, not left to rot. The licence lets anyone fork it and carry on.
