# Sonorium for Android

The Android app for [Sonorium](https://github.com/synssins/sonorium). It links
to a Sonorium server over pinned HTTPS and shows the server's own web UI, with
native playback, Cast, QR scanning and the lock-screen controls.

The server API it uses: `docs/APP_API.md` in the server repo (branch `fixes`).

| | |
|---|---|
| Package | `app.sonorium` (debug builds: `app.sonorium.debug`, installable side by side) |
| Android | 8.0 (API 26) and later; targets API 36 |
| Build | Gradle wrapper, JDK 17+, Android SDK platform 37 |

## Build

```sh
./gradlew testDebugUnitTest assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
```

Every push runs the tests, lint and a debug build on GitHub Actions; the debug
APK is attached to the run as an artifact.

## Releases

Pushing a tag `v*` builds a signed APK and publishes it as a GitHub release
(a tag with a `-`, such as `v0.1.0-beta1`, is marked pre-release):

```sh
git tag v0.1.0 && git push origin v0.1.0
```

### Signing key (one-time)

All sideloaded releases are signed with one key, so updates install over each
other and the `sonorium.app/link` App Link stays verified.

1. Create the keystore on your own machine (keytool comes with any JDK):

   ```sh
   keytool -genkeypair -v -keystore sonorium-release.jks -alias sonorium \
     -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Synssins"
   ```

2. Back up `sonorium-release.jks` and its password outside git. A lost key
   means users must uninstall to get later versions.
3. Add four repository secrets (Settings › Secrets and variables › Actions):

   | Secret | Value |
   |---|---|
   | `SONORIUM_KEYSTORE_BASE64` | `base64 -w0 sonorium-release.jks` (PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("sonorium-release.jks"))`) |
   | `SONORIUM_KEYSTORE_PASSWORD` | the keystore password |
   | `SONORIUM_KEY_ALIAS` | `sonorium` |
   | `SONORIUM_KEY_PASSWORD` | the key password (the same as the keystore password unless you set another) |

4. The SHA-256 fingerprint for `assetlinks.json`:

   ```sh
   keytool -list -v -keystore sonorium-release.jks -alias sonorium | grep SHA256
   ```

   The release job also prints it in its log.

For local signed builds, put the same four values in an untracked
`keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`).

**Google Play later:** when enrolling in Play App Signing, choose to upload
this same key as the app signing key. Play builds then carry the same
signature as the sideloaded ones, so a sideloaded install updates from Play
without uninstalling, and `assetlinks.json` keeps one fingerprint.
