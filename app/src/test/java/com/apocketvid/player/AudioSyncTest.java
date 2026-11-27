package com.apocketvid.player;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AudioSyncTest {
    @Test public void parsesSignedSecondsAndSubTenthOffsets() {
        assertEquals(125, AudioSync.parseDelayMs("+0.125"));
        assertEquals(-50, AudioSync.parseDelayMs("-.05"));
        assertEquals(-10000, AudioSync.parseDelayMs("-10"));
    }

    @Test public void acceptsDecimalCommaAndSurroundingWhitespace() {
        assertEquals(-250, AudioSync.parseDelayMs(" -0,25 "));
    }

    @Test public void roundsToTheSavedMillisecondResolution() {
        assertEquals(1, AudioSync.parseDelayMs("0.0009"));
        assertEquals(-1, AudioSync.parseDelayMs("-0.0009"));
    }

    @Test public void formatsSignedValuesWithoutLosingSavedPrecision() {
        assertEquals("+0.0", AudioSync.formatDelay(0));
        assertEquals("-0.25", AudioSync.formatDelay(-250));
        assertEquals("+0.125", AudioSync.formatDelay(125));
        assertEquals("+10.0", AudioSync.formatDelay(10000));
    }

    @Test public void plusAndMinusStepsStayWithinTheAllowedRange() {
        assertEquals(100, AudioSync.adjustDelayMs(0, 100));
        assertEquals(-100, AudioSync.adjustDelayMs(0, -100));
        assertEquals(10000, AudioSync.adjustDelayMs(9950, 100));
        assertEquals(-10000, AudioSync.adjustDelayMs(-9950, -100));
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsOutOfRangeOffsets() {
        AudioSync.parseDelayMs("10.001");
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsIncompleteInput() {
        AudioSync.parseDelayMs("-");
    }
}
