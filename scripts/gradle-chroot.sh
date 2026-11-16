#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

PROJECT_ROOT="$PWD"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$PROJECT_ROOT/.local/android-sdk"
export ANDROID_DATA="$PROJECT_ROOT/.local/android-data"
mkdir -p "$ANDROID_DATA" "$PROJECT_ROOT/.local/gradle-home"

exec "$PROJECT_ROOT/.local/gradle/gradle-8.11.1/bin/gradle" \
    --gradle-user-home "$PROJECT_ROOT/.local/gradle-home" --no-daemon \
    -Pandroid.aapt2FromMavenOverride="$PROJECT_ROOT/.local/android-native/build-tools/aapt2" "$@"
