package com.apocketvid.player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class PlaybackOptions {
    static final int LOCAL_CACHE_MS = 75;
    private PlaybackOptions() {}

    static List<String> create() {
        // LibVLC appends audio output and display defaults in its constructor.
        return new ArrayList<>(Arrays.asList(
                "--no-video-title-show", "--avcodec-hw=none", "--codec=all",
                "--file-caching=" + LOCAL_CACHE_MS, "--no-input-fast-seek",
                "--no-metadata-network-access", "--no-sub-autodetect-file"));
    }
}
