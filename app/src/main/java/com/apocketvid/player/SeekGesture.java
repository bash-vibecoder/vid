package com.apocketvid.player;

final class SeekGesture {
    private static final float FIRST_SEGMENT_SCREEN_FRACTION = 0.2f;
    private static final double INITIAL_MS_PER_SCREEN_WIDTH = 25000.0;
    private static final double CONTINUED_MS_PER_SCREEN_WIDTH = 50000.0;

    private SeekGesture() {}

    static int seekDeltaMs(float distancePx, float screenWidthPx) {
        if (screenWidthPx <= 0 || Float.isNaN(distancePx) || Float.isInfinite(distancePx)) return 0;
        float distance = Math.abs(distancePx);
        float firstSegmentPx = screenWidthPx * FIRST_SEGMENT_SCREEN_FRACTION;
        float initialDistancePx = Math.min(distance, firstSegmentPx);
        float continuedDistancePx = Math.max(0, distance - firstSegmentPx);
        double delta = (initialDistancePx * INITIAL_MS_PER_SCREEN_WIDTH
                + continuedDistancePx * CONTINUED_MS_PER_SCREEN_WIDTH) / screenWidthPx;
        return (int) Math.round(Math.copySign(delta, distancePx));
    }
}
