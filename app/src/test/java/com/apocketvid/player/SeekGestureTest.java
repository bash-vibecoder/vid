package com.apocketvid.player;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SeekGestureTest {
    @Test public void firstScreenFifthSeeksFiveSeconds() {
        assertEquals(5000, SeekGesture.seekDeltaMs(200, 1000));
    }

    @Test public void continuedDragDoublesSensitivityAfterFirstFiveSeconds() {
        assertEquals(15000, SeekGesture.seekDeltaMs(400, 1000));
    }

    @Test public void leftwardDragUsesTheSameSensitivityInReverse() {
        assertEquals(-15000, SeekGesture.seekDeltaMs(-400, 1000));
    }

    @Test public void invalidScreenWidthProducesNoSeek() {
        assertEquals(0, SeekGesture.seekDeltaMs(200, 0));
    }
}
