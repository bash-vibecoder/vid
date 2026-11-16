#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

PREVIEW_REV=b22f62c82f0639506555bfba7eff51c04cb0f0b6
PREVIEW_SOURCE_SHA=e8b98b184ad0cb34900ece0b315c3a495d2bd3e8b7ab9a0d6062c6871e8c0097
PREVIEW_AAR_SHA=cbc820f5f16834b15fd57a4c1b46653fc76689d1bbccd253c87d96252838e7c5
mkdir -p .local/native-preview/lib .local/deps app/src/main/jniLibs/arm64-v8a
PREVIEW_ARCHIVE=.local/deps/ffmpeg-retriever-source.tar.gz
PREVIEW_AAR=.local/deps/ffmpeg-retriever-native.aar
if ! test -f "$PREVIEW_ARCHIVE"; then
    curl -fL "https://codeload.github.com/wseemann/FFmpegMediaMetadataRetriever/tar.gz/$PREVIEW_REV" -o "$PREVIEW_ARCHIVE"
fi
if ! test -f "$PREVIEW_AAR"; then
    curl -fL 'https://repo.maven.apache.org/maven2/com/github/wseemann/FFmpegMediaMetadataRetriever-native/1.0.23/FFmpegMediaMetadataRetriever-native-1.0.23.aar' -o "$PREVIEW_AAR"
fi
python3 - "$PREVIEW_ARCHIVE" "$PREVIEW_SOURCE_SHA" "$PREVIEW_AAR" "$PREVIEW_AAR_SHA" <<'PYBUILD'
from pathlib import Path
import hashlib,sys,tarfile,zipfile
archive,source_sha,aar,aar_sha=sys.argv[1:]
for filename,expected in [(archive,source_sha),(aar,aar_sha)]:
    if hashlib.sha256(Path(filename).read_bytes()).hexdigest()!=expected:
        raise SystemExit('Preview dependency checksum failed: '+filename)
with tarfile.open(archive) as source:
    for member in source.getmembers():
        marker='/native/src/main/jni/ffmpeg/ffmpeg/arm64-v8a/include/'
        if marker not in member.name or not member.isfile(): continue
        relative=Path(member.name.split(marker,1)[1])
        if relative.is_absolute() or '..' in relative.parts: raise SystemExit('Invalid header path')
        destination=Path('.local/native-preview/include')/relative
        destination.parent.mkdir(parents=True,exist_ok=True)
        destination.write_bytes(source.extractfile(member).read())
with zipfile.ZipFile(aar) as source:
    for name in source.namelist():
        if name.startswith('jni/arm64-v8a/') and name.endswith('.so'):
            (Path('.local/native-preview/lib')/Path(name).name).write_bytes(source.read(name))
PYBUILD

PREVIEW_FLAGS=(-I.local/native-preview/include -O2 -fPIC -fvisibility=hidden -fstack-protector-strong
    -D_FORTIFY_SOURCE=2 -Wall -Wextra -Werror -shared
    -Wl,-z,relro,-z,now,-z,noexecstack,-z,max-page-size=16384,--no-undefined,-soname,libapocketvid_preview.so
    -L.local/native-preview/lib native/preview/preview.c -lavformat -lavcodec -lswscale -lavutil)
if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
    PREVIEW_COMPILER="$(find "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt" -path '*/bin/aarch64-linux-android26-clang' -print -quit)"
    test -n "$PREVIEW_COMPILER"
    "$PREVIEW_COMPILER" "${PREVIEW_FLAGS[@]}" -lm -o app/src/main/jniLibs/arm64-v8a/libapocketvid_preview.so
else
    # ARM64 Ubuntu chroot: use its clang with the existing Android/Termux headers and Bionic.
    PREVIEW_ANDROID_ROOT=/proc/1/root
    PREVIEW_SYSROOT="$PREVIEW_ANDROID_ROOT/data/data/com.termux/files/usr"
    PREVIEW_JDK="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}"
    test -f "$PREVIEW_SYSROOT/include/stdint.h" || { echo 'Set ANDROID_NDK_HOME to rebuild the preview bridge.' >&2; exit 1; }
    clang --target=aarch64-linux-android26 -fuse-ld=lld --sysroot="$PREVIEW_SYSROOT" \
        -isystem "$PREVIEW_SYSROOT/include" -isystem "$PREVIEW_SYSROOT/include/aarch64-linux-android" \
        -isystem "$PREVIEW_JDK/include" -isystem "$PREVIEW_JDK/include/linux" -nostdlib \
        "${PREVIEW_FLAGS[@]}" "$PREVIEW_ANDROID_ROOT/apex/com.android.runtime/lib64/bionic/libc.so" \
        "$PREVIEW_ANDROID_ROOT/apex/com.android.runtime/lib64/bionic/libm.so" \
        -o app/src/main/jniLibs/arm64-v8a/libapocketvid_preview.so
fi
sha256sum app/src/main/jniLibs/arm64-v8a/libapocketvid_preview.so
