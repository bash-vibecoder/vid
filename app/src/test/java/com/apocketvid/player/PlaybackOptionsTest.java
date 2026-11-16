package com.apocketvid.player;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaybackOptionsTest {
    @Test public void libVlcCanAppendItsDefaultOptions() {
        List<String> options = PlaybackOptions.create();

        // LibVLC 3.7.7's constructor adds these before creating its native engine.
        options.add("--aout=android_audiotrack");
        options.add("--android-display-chroma");
        options.add("RV16");

        assertTrue(options.contains("--avcodec-hw=none"));
        assertTrue(options.contains("--codec=all"));
    }

    @Test public void libVlcDefaultsDoNotCarryOverToAnotherPlayer() {
        List<String> firstPlayer = PlaybackOptions.create();
        firstPlayer.add("--aout=android_audiotrack");

        assertFalse(PlaybackOptions.create().contains("--aout=android_audiotrack"));
    }
}
