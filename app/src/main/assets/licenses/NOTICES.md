# Third-party software

APocketVid includes LibVLC Android and its native codec engine, plus the dependencies resolved from its Maven POM. These components retain their upstream licenses and copyrights.

## LibVLC Android 3.7.7

- Artifact: `org.videolan.android:libvlc-all:3.7.7`.
- Binary: https://repo.maven.apache.org/maven2/org/videolan/android/libvlc-all/3.7.7/libvlc-all-3.7.7.aar
- AAR SHA-256: `b48dab96e0e90e34cce3861963500c144a5aadb1b249d501d6a4b104d849e61b`.
- License: GNU Lesser General Public License, version 2.1 or later; see `app/src/main/assets/licenses/LGPL-2.1.txt`.
- Copyright: VideoLAN, VLC authors, and the respective contributors.
- Android bindings and build scripts: https://code.videolan.org/videolan/libvlcjni (binding revision `0b8dc65`).
- Java sources: https://repo.maven.apache.org/maven2/org/videolan/android/libvlc-all/3.7.7/libvlc-all-3.7.7-sources.jar
- VLC source: https://code.videolan.org/videolan/vlc (core revision `586dd059ac`).

The app uses the official ARM64 shared libraries from the AAR, without changes to codec source. LibVLC incorporates FFmpeg/libavcodec and other upstream components, including software video/audio decoders, subtitle renderers, and supporting libraries. Their build recipes and pinned source revisions are maintained in VLC's `contrib` tree and the libvlcjni build scripts above. See those source trees for each component's full notices and license terms. Android's graphics and PCM audio output libraries remain platform services.

The native engine is dynamically linked. This source project and the pinned Maven dependency allow rebuilding the app with a compatible replacement of the LGPL libraries. Modifying or reverse engineering the LGPL components for debugging such modifications is permitted under their license. No extra restriction on those rights is imposed here.

## FFmpeg libraries distributed with FFmpegMediaMetadataRetriever 1.0.23

Previews use the ARM64 FFmpeg and OpenSSL shared libraries distributed in `com.github.wseemann:FFmpegMediaMetadataRetriever-native:1.0.23`. Copyright William Seemann, FFmpeg contributors, and the respective upstream authors. The FFmpeg components retain their LGPL 2.1 or later license; OpenSSL retains its upstream license. License texts are packaged in `assets/licenses/`. APocketVid uses its own JNI extraction bridge from `native/preview/preview.c`; the upstream Java retriever and retriever JNI library are not packaged.

- Source, build recipes, and upstream notices: https://github.com/wseemann/FFmpegMediaMetadataRetriever/tree/v1.0.23 (revision `b22f62c82f0639506555bfba7eff51c04cb0f0b6`).
- Artifacts: https://repo.maven.apache.org/maven2/com/github/wseemann/
- Native AAR SHA-256: `cbc820f5f16834b15fd57a4c1b46653fc76689d1bbccd253c87d96252838e7c5`.

The FFmpeg/OpenSSL shared libraries are packaged from the published AAR without changes. The separate APocketVid JNI bridge is compiled from the included source and dynamically links to those libraries. The preview engine does not provide network features to this app; the APK has no Internet permission.

The AAR's OpenSSL 1.1.1w notices are included in `assets/licenses/OpenSSL-1.1.1w.txt`, with source at https://github.com/openssl/openssl/tree/OpenSSL_1_1_1w. The FFmpeg LGPL text is included in `assets/licenses/LGPL-2.1.txt`.

## AndroidX and Kotlin runtime

The Gradle dependency graph includes AndroidX support libraries (Android Open Source Project contributors) and the Kotlin standard library (JetBrains and contributors). They are distributed under the Apache License 2.0, included in `app/src/main/assets/licenses/Apache-2.0.txt`. Upstream sources:

- https://android.googlesource.com/platform/frameworks/support/
- https://github.com/JetBrains/kotlin/tree/v2.2.10

## LLVM libc++

The LibVLC AAR includes `libc++_shared.so`, LLVM's C++ standard library. LLVM is distributed under the Apache License 2.0 with LLVM exceptions. Its full license is included in `app/src/main/assets/licenses/LLVM.txt`; upstream source and notices are at https://github.com/llvm/llvm-project/tree/main/libcxx.

The exact packaged versions can be inspected with `./scripts/gradle-chroot.sh :app:dependencies --configuration debugRuntimeClasspath`. Build tools are not part of the app's runtime.
