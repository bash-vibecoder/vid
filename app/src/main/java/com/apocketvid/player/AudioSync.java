package com.apocketvid.player;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class AudioSync {
    private static final int MIN_DELAY_MS = -10000;
    private static final int MAX_DELAY_MS = 10000;

    private AudioSync() {}

    static int parseDelayMs(String text) {
        BigDecimal seconds = new BigDecimal(text.trim().replace(',', '.'));
        if (seconds.abs().compareTo(BigDecimal.TEN) > 0) {
            throw new IllegalArgumentException("Audio offset must be between -10 and +10 seconds");
        }
        return seconds.movePointRight(3).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    static String formatDelay(int delayMs) {
        BigDecimal seconds = BigDecimal.valueOf(delayMs, 3).stripTrailingZeros();
        if (seconds.scale() < 1) seconds = seconds.setScale(1);
        return (delayMs >= 0 ? "+" : "") + seconds.toPlainString();
    }

    static int adjustDelayMs(int delayMs, int adjustmentMs) {
        return (int) Math.max(MIN_DELAY_MS, Math.min(MAX_DELAY_MS, (long) delayMs + adjustmentMs));
    }
}
