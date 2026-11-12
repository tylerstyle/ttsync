# Releasing TT Calendar Sync

How to build, sign and publish a release (DESIGN.md section 11). Commands run in the repo root on
the build machine with `ANDROID_HOME=~/Android/Sdk`. `BT=$ANDROID_HOME/build-tools/37.0.0` below.

## 1. The signing key (once)

Android installs an update only if it is signed with the same key as the installed app. **If the key is
lost, nobody can update any more**: every user would have to remove the copies, uninstall and install
a new app. If it leaks, someone else can publish "updates". So:

- Exactly one key, `~/keys/ttsync/release.jks` on the build machine, outside every git repo. Never commit it,
  never copy it into the repo, never put it in CI.
- RSA 4096, alias `ttsync`, valid for 30 years (10950 days).

Create it:

```sh
mkdir -p ~/keys/ttsync && chmod 700 ~/keys/ttsync
keytool -genkeypair -v \
  -keystore ~/keys/ttsync/release.jks -storetype PKCS12 \
  -alias ttsync -keyalg RSA -keysize 4096 -validity 10950 \
  -dname "CN=TT Calendar Sync"
chmod 600 ~/keys/ttsync/release.jks
```

- keytool asks for the password. Use a long random one from the password manager. A PKCS12 keystore
  has a single password, so the key password is the same as the store password.
- The `-dname` becomes part of the public certificate. Don't put private details in it.

Back it up right away, before the first release:

- Store `release.jks` as an attachment in the password manager entry "TT Calendar Sync release key",
  together with the password and the alias.
- Keep a second, offline copy, for example on an encrypted USB stick that isn't kept next to the build machine.
- Check every copy once: `keytool -list -v -keystore <copy> -alias ttsync` must ask for the password
  and show the same SHA-256 fingerprint.

Write down the certificate fingerprint. It goes into the README and every release note, in the
`AB:CD:…` form keytool prints, which is also the form the app's About screen shows:

```sh
keytool -list -v -keystore ~/keys/ttsync/release.jks -alias ttsync | grep 'SHA256:'
```

## 2. Gradle configuration (once per machine)

The build reads four values, each from a Gradle property or else from an environment variable. If all
four are missing, `assembleRelease` builds an unsigned `app-release-unsigned.apk`. If only some are
set, Gradle warns, names the missing ones and also builds unsigned.

| Gradle property | Environment variable | Value |
|---|---|---|
| `ttsync.signing.storeFile` | `TTSYNC_KEYSTORE` | `~/keys/ttsync/release.jks` (`~/` works too) |
| `ttsync.signing.storePassword` | `TTSYNC_KEYSTORE_PASSWORD` | the keystore password |
| `ttsync.signing.keyAlias` | `TTSYNC_KEY_ALIAS` | `ttsync` |
| `ttsync.signing.keyPassword` | `TTSYNC_KEY_PASSWORD` | the same password (PKCS12) |

Put them in `~/.gradle/gradle.properties` (not the repo's `gradle.properties`) and run
`chmod 600 ~/.gradle/gradle.properties`. Don't type the passwords into commands, where shell history
would keep them.

## 3. Release steps

Example for 0.1.0; replace the version everywhere.

1. **Ready.** `main` is clean, and the device checklist (DESIGN.md section 10, steps 0–13) passed with
   the debug build.
2. **Version.** In `app/build.gradle.kts`, set `appVersionName = "0.1.0"`. The `versionCode` follows
   from it (`major·10000 + minor·100 + patch`) and must grow with every release. Commit it
   (`git commit -am "Release 0.1.0"`), so the APK is built from a commit; amend it if a later step
   needs a fix.
3. **Build and test:**

   ```sh
   ./gradlew --no-daemon clean assembleRelease testDebugUnitTest lintRelease
   ```

   The result is `app/build/outputs/apk/release/app-release.apk`. If it is called
   `app-release-unsigned.apk`, the signing configuration is missing (section 2).
4. **Check the APK:**

   ```sh
   scripts/release-check.sh
   ```

   It fails if the APK is debuggable or contains the debug-only code (`DebugReceiver`, the raw API
   dump), checks that R8 kept the worker and the serializers, and runs
   `$BT/apksigner verify --verbose --print-certs`. Check that the output says
   `Verified using v2 scheme (APK Signature Scheme v2): true`, that the key size is 4096 and that
   `certificate SHA-256 digest` is the fingerprint from section 1 (apksigner prints it in lower case
   without colons). Note the APK's SHA-256 at the end. An unsigned APK makes the script exit with 3.
5. **Test on the phone** (DESIGN.md section 10, step 14). A release build can't update the debug
   build (different keys), so uninstall the debug build first; the copies stay and the release build
   adopts them. Repeat steps 2–8 of the checklist with the release APK. Release builds have no
   `DebugReceiver`, so use "Sync now" on the Status screen; their logcat only shows warnings and
   errors (no `run finished` line). About must show the same certificate SHA-256.
6. **Name the files and keep the R8 mapping**, outside the repo (APKs never go into git):

   ```sh
   D=~/releases/ttsync/0.1.0 && mkdir -p $D
   cp app/build/outputs/apk/release/app-release.apk $D/ttsync-0.1.0.apk
   cp app/build/outputs/mapping/release/mapping.txt $D/ttsync-0.1.0-mapping.txt
   (cd $D && sha256sum ttsync-0.1.0.apk > ttsync-0.1.0.apk.sha256)
   ```

   The mapping file turns obfuscated stack traces from users back into code:
   `$ANDROID_HOME/cmdline-tools/latest/bin/retrace $D/ttsync-0.1.0-mapping.txt trace.txt`
   (install `cmdline-tools;latest` with `android sdk install` if it is missing).
7. **Tag and push:**

   ```sh
   git tag -a v0.1.0 -m "TT Calendar Sync 0.1.0"
   git push origin main v0.1.0
   ```

8. **GitHub release.** One APK per release, so Obtainium picks the right file:

   ```sh
   gh release create v0.1.0 $D/ttsync-0.1.0.apk $D/ttsync-0.1.0.apk.sha256 \
     $D/ttsync-0.1.0-mapping.txt --title "TT Calendar Sync 0.1.0" --notes-file notes.md
   ```

   `notes.md` contains the changes, then:

   ```text
   APK SHA-256: <sha256 of ttsync-0.1.0.apk>
   Signing certificate SHA-256: <fingerprint from section 1>
   ```

   Obtainium only looks at `.apk` assets, so the checksum and mapping files don't confuse it.
9. **Afterwards.** Install from the release page on a phone via Obtainium (section 4) and check that
   the update is offered and installs in place.

## 4. Obtainium

Users add the app once:

1. Install Obtainium (from its GitHub releases or F-Droid).
2. Add app → App source URL: the GitHub repo URL (for example `https://github.com/<owner>/<repo>`).
3. Install. Obtainium then checks the releases and offers each new APK.

Android itself refuses updates that are signed with another key, so a tampered APK can't replace the
app. On the first install, users compare the certificate SHA-256 under About with the one in the
README.

## 5. Later

- **README fingerprint and source link.** After the key exists, put its SHA-256 into `README.md`.
  When the GitHub repo exists, set `SOURCE_URL` in `ui/about/AboutScreen.kt` and the repo links in
  the README.
- **IzzyOnDroid.** It rejects APKs with Google's encrypted dependency metadata block. Before applying,
  add `dependenciesInfo { includeInApk = false; includeInBundle = false }` to the `android` block.
- **Developer verification.** Google's developer verification isn't enforced in Germany before its
  2027 rollout, so v1 stays unregistered. Decide before then; registering needs this same key.
