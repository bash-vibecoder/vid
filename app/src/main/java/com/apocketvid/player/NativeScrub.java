package com.apocketvid.player;

import android.view.Surface;

/** Persistent, local-only scrub decoder. Loaded only by the private scrub service. */
final class NativeScrub {
    static { NativePreview.ensureLoaded(); }

    static native long open(int fd, long offset, long length, Surface surface, int[] size);
    static native long pump(long decoder, long positionUs, boolean display);
    static native void close(long decoder);

    private NativeScrub() {}
}
