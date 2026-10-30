# Releasing

How a version gets from `main` to an APK people can install. The first time includes creating the signing key.

## Once: the signing key

Every release must be signed with the same key, or Android won't install it over the last one (people would have to uninstall and set up again). Keep the key **outside the repo**, and keep its passwords in a password manager, with a backup of the `.jks` file somewhere safe.

1. Create it (asks for a password and a name):
   ```powershell
   keytool -genkeypair -v -keystore $HOME\keys\tv-kiosk-release.jks -alias release -keyalg RSA -keysize 4096 -validity 10000
   ```
2. Add four repository secrets (GitHub → the repo → Settings → Secrets and variables → Actions → New repository secret):

   | Secret | Value |
   |---|---|
   | `KEYSTORE_BASE64` | the `.jks` file as base64: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("$HOME\keys\tv-kiosk-release.jks")) \| Set-Clipboard`, then paste |
   | `KEYSTORE_PASSWORD` | the keystore password |
   | `KEY_ALIAS` | `release` |
   | `KEY_PASSWORD` | the key password (the same as the keystore password unless you chose another) |

## Each release

1. On `main`, set `appVersion` in `app/build.gradle.kts` (for the first release it's already `0.1.0`).
2. In `CHANGELOG.md`, change `(unreleased)` to the date. For the first release, also remove "the first one, v0.1.0, is on its way…" from the README's Install section.
3. Commit, push, and wait for CI to pass.
4. Tag and push the tag:
   ```bash
   git tag v0.1.0
   git push origin v0.1.0
   ```
   The **Release** workflow checks the tag matches `appVersion`, runs the tests, builds the signed APK and creates a **draft** release with `tv-kiosk-v0.1.0.apk` and its `.sha256`.
5. Download the APK from the draft and check it on a TV:
   - `adb install -r tv-kiosk-v0.1.0.apk`;
   - setup finds Home Assistant, signs in and lists dashboards;
   - a pop-up from Home Assistant shows, with its picture.
6. Publish the draft (edit the release → paste the changelog section → Publish).

If the workflow fails at "Build the signed APK", a secret is missing or a password is wrong; fix the secret, delete the tag (`git push origin :v0.1.0`, `git tag -d v0.1.0`) and tag again.
