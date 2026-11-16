package com.apocketvid.player;

import org.junit.Test;
import static org.junit.Assert.*;

public class PreviewFailuresTest {
    @Test public void crashedFileIsSkippedAndOtherVideosCanContinue() {
        PreviewFailures failures = new PreviewFailures();
        failures.failed("broken-video", true);
        assertFalse(failures.canExtract("broken-video"));
        assertTrue(failures.canExtract("another-video"));
    }

    @Test public void repeatedNativeCrashesStopTheRestartLoop() {
        PreviewFailures failures = new PreviewFailures();
        failures.failed("one", true);
        failures.failed("two", true);
        failures.failed("three", true);
        assertFalse(failures.canExtract("four"));
    }

    @Test public void OrdinaryUnsupportedFilesDoNotExhaustTheCrashLimit() {
        PreviewFailures failures = new PreviewFailures();
        for (int i = 0; i < 20; i++) failures.failed("unsupported-" + i, false);
        assertTrue(failures.canExtract("good-video"));
        failures.recovered("unsupported-0");
        assertTrue(failures.canExtract("unsupported-0"));
    }
}
