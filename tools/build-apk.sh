#!/usr/bin/env bash
# Build UidHide: compile-only Xposed stubs -> javac -> d8 -> aapt2 -> sign.
set -euo pipefail

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) BIN=.exe; SCRIPT=.bat ;;
  *)                    BIN=;     SCRIPT= ;;
esac

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[ -n "$SDK" ] || {
  echo "ANDROID_SDK_ROOT is not set (point it at the Android SDK)"
  exit 1
}

BT="$(ls -d "$SDK/build-tools"/* 2>/dev/null | sort -V | tail -1)"
PLATFORM="$(ls -d "$SDK/platforms"/android-* 2>/dev/null | sort -V | tail -1)/android.jar"
[ -n "$BT" ] && [ -f "$PLATFORM" ] || {
  echo "build-tools or platform not found under $SDK"
  exit 1
}

ROOT="$(cd "$(dirname "$0")/.." && { pwd -W 2>/dev/null || pwd; })"
OUT="$ROOT/build"
DIST="$ROOT/dist"

rm -rf "$OUT"
mkdir -p "$OUT/stubs" "$OUT/classes" "$OUT/dex" "$DIST"

echo "[1/5] stubs"
find "$ROOT/stubs" -name '*.java' > "$OUT/stub_sources.txt"
javac --release 11 -nowarn -d "$OUT/stubs" @"$OUT/stub_sources.txt"
jar cf "$OUT/xposed-stubs.jar" -C "$OUT/stubs" .

echo "[2/5] module"
find "$ROOT/app/src" -name '*.java' > "$OUT/sources.txt"
javac --release 11 -nowarn -encoding UTF-8 -cp "$PLATFORM;$OUT/xposed-stubs.jar" -d "$OUT/classes" @"$OUT/sources.txt"

echo "[3/5] dex"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8$SCRIPT" --release --min-api 33 --lib "$PLATFORM" --output "$OUT/dex" @"$OUT/classes.txt"

echo "[4/5] package"
"$BT/aapt2$BIN" compile --dir "$ROOT/app/res" -o "$OUT/res.zip"
"$BT/aapt2$BIN" link -o "$OUT/base.apk" -I "$PLATFORM" "$OUT/res.zip" \
    --manifest "$ROOT/app/AndroidManifest.xml" \
    --min-sdk-version 33 --target-sdk-version 36 \
    -A "$ROOT/app/assets"
python - "$OUT" <<'PY'
import sys, zipfile, os
out = sys.argv[1]
apk = os.path.join(out, "unsigned.apk")
with open(os.path.join(out, "base.apk"), "rb") as src, open(apk, "wb") as dst:
    dst.write(src.read())
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(os.path.join(out, "dex", "classes.dex"), "classes.dex")
PY

echo "[5/5] align + sign"
# Release key: kept outside the repo; the properties file below names it
PROPS="${RELEASE_PROPS:-$HOME/.android/uidhide-release.properties}"
[ -f "$PROPS" ] || { echo "missing $PROPS"; exit 1; }
prop() { sed -n "s/^$1=//p" "$PROPS" | head -1; }
KS="$(prop storeFile)"
command -v cygpath >/dev/null 2>&1 && KS="$(cygpath -w "$KS")"
KSPASS="$(prop storePassword)"
ALIAS="$(prop keyAlias)"
KEYPASS="$(prop keyPassword)"
"$BT/zipalign$BIN" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner$SCRIPT" sign --ks "$KS" --ks-pass "pass:$KSPASS" --ks-key-alias "$ALIAS" \
    --key-pass "pass:$KEYPASS" --out "$DIST/uidhide-0.1.1.apk" "$OUT/aligned.apk"
"$BT/apksigner$SCRIPT" verify --print-certs "$DIST/uidhide-0.1.1.apk" | head -4
ls -la "$DIST/uidhide-0.1.1.apk"
