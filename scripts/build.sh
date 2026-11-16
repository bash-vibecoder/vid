#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

PROJECT_ROOT="$PWD"
mkdir -p .local dist
KEYSTORE="$PROJECT_ROOT/.local/pocket-video-debug.jks"
if ! test -f "$KEYSTORE"; then
    keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
        -alias pocketvideo -keyalg RSA -keysize 2048 -validity 10000 \
        -dname 'CN=APocketVid Development' -noprompt
fi
if test -x .local/gradle/gradle-8.11.1/bin/gradle \
        && test -x .local/android-native/build-tools/aapt2; then
    ./scripts/gradle-chroot.sh :app:assembleDebug "$@"
    BUILD_SDK_ROOT="$PROJECT_ROOT/.local/android-sdk"
    ALIGN_TOOL="$PROJECT_ROOT/.local/android-native/build-tools/zipalign"
    MANIFEST_TOOL="$PROJECT_ROOT/.local/android-native/build-tools/aapt"
else
    "${GRADLE:-gradle}" :app:assembleDebug "$@"
    BUILD_SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    ALIGN_TOOL="$BUILD_SDK_ROOT/build-tools/35.0.0/zipalign"
    MANIFEST_TOOL="$BUILD_SDK_ROOT/build-tools/35.0.0/aapt"
fi

SIGN_TOOL="$BUILD_SDK_ROOT/build-tools/35.0.0/apksigner"
test -x "$SIGN_TOOL" || { echo 'Set ANDROID_HOME to an SDK with build tools 35.0.0.' >&2; exit 1; }
test -x "$ALIGN_TOOL" || { echo 'Missing native zipalign tool.' >&2; exit 1; }
export ANDROID_DATA="${ANDROID_DATA:-$PROJECT_ROOT/.local/android-data}"
mkdir -p "$ANDROID_DATA"

VERSION="$(sed -n "s/^[[:space:]]*versionName '\([^']*\)'.*/\1/p" app/build.gradle)"
test -n "$VERSION"
OUTPUT="dist/APocketVid-v${VERSION}.apk"
STAGED_OUTPUT="dist/.APocketVid-v${VERSION}.apk"
trap 'rm -f "$STAGED_OUTPUT"' EXIT
cp app/build/outputs/apk/debug/app-debug.apk "$STAGED_OUTPUT"
"$SIGN_TOOL" verify --verbose "$STAGED_OUTPUT"
"$ALIGN_TOOL" -c -P 16 4 "$STAGED_OUTPUT"
APK_PERMISSIONS="$("$MANIFEST_TOOL" dump permissions "$STAGED_OUTPUT")"
if [[ "$APK_PERMISSIONS" == *android.permission.INTERNET* ]]; then
    echo 'Offline build check failed: the APK requests Internet access.' >&2
    exit 1
fi
mv "$STAGED_OUTPUT" "$OUTPUT"
(cd dist && sha256sum "APocketVid-v${VERSION}.apk" > "APocketVid-v${VERSION}.apk.sha256")
ls -lh "$OUTPUT"
