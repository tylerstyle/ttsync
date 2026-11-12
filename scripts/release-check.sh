#!/usr/bin/env bash
# Checks a release APK before it is published (docs/RELEASING.md). Read-only.
#
# Usage: scripts/release-check.sh [apk] [mapping.txt]
#   apk          default: app/build/outputs/apk/release/app-release.apk
#   mapping.txt  default: app/build/outputs/mapping/release/mapping.txt (R8 mapping of that build)
#
# Fails if the APK is debuggable or contains the debug-only code (debug.DebugReceiver, the raw API
# dump timetree.FileRawResponseSink), or if a signed APK does not verify. Prints the version, the
# signing certificate and the APK's SHA-256 for the release notes. Exits with 3 if the APK is
# otherwise fine but unsigned.

set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
apk=${1:-$root/app/build/outputs/apk/release/app-release.apk}
mapping=${2:-$root/app/build/outputs/mapping/release/mapping.txt}
sdk=${ANDROID_HOME:-$HOME/Android/Sdk}
bt=${BUILD_TOOLS:-$sdk/build-tools/37.0.0}

fail() { echo "FAIL: $*" >&2; exit 1; }
[[ -f $apk ]] || fail "no APK at $apk"
for tool in aapt2 dexdump apksigner; do [[ -x $bt/$tool ]] || fail "missing $bt/$tool"; done

echo "APK: $apk ($(stat -c %s "$apk") bytes)"
"$bt/aapt2" dump badging "$apk" | grep -E '^package:' | sed 's/ platformBuild.*//'

manifest=$("$bt/aapt2" dump xmltree --file AndroidManifest.xml "$apk")
if grep -q 'DebugReceiver' <<<"$manifest"; then fail "the manifest declares DebugReceiver"; fi
if grep -Eq 'android:debuggable.*=(true|-1)' <<<"$manifest"; then fail "the APK is debuggable"; fi
echo "OK   manifest: no DebugReceiver, not debuggable"

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
unzip -q -o "$apk" 'classes*.dex' -d "$tmp"
classes=$("$bt/dexdump" "$tmp"/classes*.dex 2>/dev/null | grep -E "^  Class descriptor" | sed "s/.*'\(.*\)'/\1/")
echo "OK   dex: $(wc -l <<<"$classes") classes"
if grep -Eq 'Lde/tergelius/ttsync/debug/|FileRawResponseSink' <<<"$classes"; then
  fail "the dex contains debug-only classes"
fi
# Name-independent: the debug actions and the dump folder are string constants of that code.
if cat "$tmp"/classes*.dex | grep -aqE 'de\.tergelius\.ttsync\.debug\.|debug/raw'; then
  fail "the dex contains strings of the debug-only code"
fi
echo "OK   dex: no DebugReceiver, no FileRawResponseSink"
if [[ -f $mapping ]]; then
  if grep -Eq '^de\.tergelius\.ttsync\.(debug\.|timetree\.FileRawResponseSink)' "$mapping"; then
    fail "the R8 mapping lists debug-only classes"
  fi
  for c in sync.SyncWorker timetree.TtEvent timetree.TtEvent\$\$serializer LoginRoute\$\$serializer; do
    grep -q "^de\.tergelius\.ttsync\.$c -> " "$mapping" || fail "R8 removed de.tergelius.ttsync.$c"
  done
  echo "OK   mapping: worker and serializers kept, no debug classes ($mapping)"
else
  echo "WARN no R8 mapping at $mapping; keep the one of this build with the release"
fi

if "$bt/apksigner" verify "$apk" >/dev/null 2>&1; then
  echo "OK   signature:"
  "$bt/apksigner" verify --verbose --print-certs "$apk" |
    grep -E '^Verified using .*: true$|Signer.*: (certificate (DN|SHA-256 digest)|key size)' |
    sed 's/^/     /'
  signed=1
else
  echo "WARN not signed, or the signature does not verify: don't publish this APK"
  signed=0
fi
echo "SHA-256: $(sha256sum "$apk" | cut -d' ' -f1)"
[[ $signed == 1 ]] || exit 3
