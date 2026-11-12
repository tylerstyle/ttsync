# Releasing TT Calendar Sync

How to build, sign and publish a release (DESIGN.md section 11). Commands run in the repo root on
the build machine, the one that holds the signing key, with `ANDROID_HOME` pointing at the Android
SDK. `BT=$ANDROID_HOME/build-tools/37.0.0` below. Releases are published at
<https://github.com/tylerstyle/ttsync/releases>.

Each release consists of:

- a GitHub release `vX.Y.Z` with exactly one APK, `ttsync-X.Y.Z.apk`, signed with the release key;
- `ttsync-X.Y.Z.apk.sha256` and the R8 mapping `ttsync-X.Y.Z-mapping.txt` as further assets;
- release notes with the changes, the APK's SHA-256 and the signing certificate's SHA-256.

Obtainium users get it as an update from the same repository URL.

## 1. The signing key (once)

Android installs an update only if it is signed with the same key as the installed app. **If the key is
lost, nobody can update any more**: every user would have to remove the copies, uninstall and install
a new app. If it leaks, someone else can publish "updates". So:

- Exactly one key, `~/keys/ttsync/release.jks` on the build machine, outside every git repo. Never
  commit it, never copy it into the repo, never put it in CI.
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
- Keep a second, offline copy, for example on an encrypted USB stick that isn't kept next to the
  build machine.
- Check every copy once: `keytool -list -v -keystore <copy> -alias ttsync` must ask for the password
  and show the same SHA-256 fingerprint.

Write down the certificate fingerprint. It goes into the README and every release note, in the
`AB:CD:…` form keytool prints, which is also the form the app's About screen shows:

```sh
keytool -list -v -keystore ~/keys/ttsync/release.jks -alias ttsync | grep 'SHA256:'
```

The release key's fingerprint (also in the README):

```text
C0:FD:19:AE:C1:8E:BB:8F:5B:B2:59:5D:4A:91:B1:19:4C:C8:9D:67:03:4C:32:FF:73:7F:E3:7A:A6:39:C1:13
```

`apksigner verify --print-certs` (build-tools 37) prints the same digest in lower case without
colons: `c0fd19aec18ebb8f5bb2595d4a91b1194cc89d67034c32ff737fe37aa639c113`.

## 2. Gradle configuration (once per machine)

The build reads four values, each from a Gradle property or else from an environment variable. If all
four are missing, `assembleRelease` builds an unsigned `app-release-unsigned.apk`. If only some are
set, Gradle warns, names the missing ones and also builds unsigned.

| Gradle property | Environment variable | Value |
|---|---|---|
| `ttsync.signing.storeFile` | `TTSYNC_KEYSTORE` | `~/keys/ttsync/release.jks` (a leading `~/` is expanded; an absolute path works too) |
| `ttsync.signing.storePassword` | `TTSYNC_KEYSTORE_PASSWORD` | the keystore password |
| `ttsync.signing.keyAlias` | `TTSYNC_KEY_ALIAS` | `ttsync` |
| `ttsync.signing.keyPassword` | `TTSYNC_KEY_PASSWORD` | the same password (PKCS12) |

Put them in `~/.gradle/gradle.properties` (not the repo's `gradle.properties`) as plain
`key=value` lines, not as the table above. Open the file in an editor and replace `<password>`:

```properties
ttsync.signing.storeFile=~/keys/ttsync/release.jks
ttsync.signing.storePassword=<password>
ttsync.signing.keyAlias=ttsync
ttsync.signing.keyPassword=<password>
```

Then run `chmod 600 ~/.gradle/gradle.properties`. Don't type the passwords into commands, where
shell history would keep them. To check the setup without showing the password, build and look
for the certificate (section 3, step 4): `apksigner verify --print-certs` must print
`V2 Signer: certificate DN: CN=TT Calendar Sync`; an `app-release-unsigned.apk` means the values
weren't read.

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
   without colons: `c0fd19ae…39c113`). Note the APK's SHA-256 at the end. An unsigned APK makes the
   script exit with 3.
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

   First release only: before the first push, check that neither the files nor the history contain
   private data (host names, home paths, device serials, raw dumps) and that the commits carry the
   author name and email you want to publish (for example GitHub's noreply address). Then create the
   repository: `gh repo create tylerstyle/ttsync --public --source . --remote origin`.

8. **GitHub release.** One APK per release, so Obtainium picks the right file:

   ```sh
   gh release create v0.1.0 $D/ttsync-0.1.0.apk $D/ttsync-0.1.0.apk.sha256 \
     $D/ttsync-0.1.0-mapping.txt --title "TT Calendar Sync 0.1.0" --notes-file notes.md
   ```

   `notes.md` contains the changes, then:

   ```text
   APK SHA-256: <sha256 of ttsync-0.1.0.apk>
   Signing certificate SHA-256: C0:FD:19:AE:C1:8E:BB:8F:5B:B2:59:5D:4A:91:B1:19:4C:C8:9D:67:03:4C:32:FF:73:7F:E3:7A:A6:39:C1:13

   Install: download ttsync-0.1.0.apk on the phone, or add https://github.com/tylerstyle/ttsync
   to Obtainium. See the README for setup.
   ```

   Obtainium only looks at `.apk` assets, so the checksum and mapping files don't confuse it. Mark
   only finished releases as releases (no drafts or pre-releases for normal versions), so Obtainium
   offers them.
9. **Afterwards.** Install from the release page on a phone via Obtainium (section 4) and check that
   the update is offered and installs in place.

## 4. Obtainium

Users add the app once:

1. Install Obtainium (from its GitHub releases or F-Droid).
2. Add app → App source URL: `https://github.com/tylerstyle/ttsync`.
3. Install. Obtainium then checks the releases and offers each new APK.

Android itself refuses updates that are signed with another key, so a tampered APK can't replace the
app. On the first install, users compare the certificate SHA-256 under About with the one in the
README.

## 5. Later

- **IzzyOnDroid.** It rejects APKs with Google's encrypted dependency metadata block. Before applying,
  add `dependenciesInfo { includeInApk = false; includeInBundle = false }` to the `android` block.
- **Developer verification.** Google's developer verification isn't enforced in Germany before its
  2027 rollout, so v1 stays unregistered. Decide before then; registering needs this same key.
