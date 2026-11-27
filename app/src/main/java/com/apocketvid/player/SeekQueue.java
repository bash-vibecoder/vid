package com.apocketvid.player;

/** Coalesces scrubbing requests and waits for decoder output before seeking again. */
final class SeekQueue {
    private static final long MIN_RENDER_SETTLE_MS = 64;
    private static final long PREVIEW_TIMEOUT_MS = 1800;
    private static final long COMMIT_TIMEOUT_MS = 3500;

    static final class Request {
        final int positionMs;
        final boolean committed;

        Request(int positionMs, boolean committed) {
            this.positionMs = Math.max(0, positionMs);
            this.committed = committed;
        }
    }

    private Request pending;
    private Request inFlight;
    private long issuedAt;
    private int framesAtIssue;
    private int settledFrames = -1;
    private boolean previewsSuspended;
    private int recoveryTarget = -1;
    private boolean scrubbing;
    private int lastRenderedPosition = -1;
    private int renderedTarget = -1;

    void beginGesture() {
        previewsSuspended = false;
        scrubbing = true;
        lastRenderedPosition = renderedTarget = -1;
    }

    void endGesture() {
        scrubbing = false;
        lastRenderedPosition = renderedTarget = -1;
    }

    boolean requestPreview(int positionMs) {
        if (previewsSuspended) return false;
        if (pending != null && pending.positionMs == positionMs) return false;
        if (pending == null && inFlight != null && inFlight.positionMs == positionMs) return false;
        if (pending == null && inFlight == null && scrubbing && lastRenderedPosition == positionMs) return false;
        pending = new Request(positionMs, false);
        return true;
    }

    void requestCommit(int positionMs) {
        if (inFlight != null && inFlight.positionMs == positionMs) {
            inFlight = new Request(positionMs, true);
            pending = null;
        } else if (scrubbing && inFlight == null && lastRenderedPosition == positionMs) {
            pending = null;
            renderedTarget = positionMs;
        } else pending = new Request(positionMs, true);
    }

    Request poll(long now, int displayedFrames, boolean playing, long playbackTimeMs) {
        if (inFlight != null) {
            long elapsed = now - issuedAt;
            long difference = playbackTimeMs - inFlight.positionMs;
            boolean atTarget = difference >= -100 && difference <= (playing ? elapsed : 0) + 250;
            boolean newFrame = displayedFrames > 0 && displayedFrames != framesAtIssue;
            boolean settled = elapsed >= MIN_RENDER_SETTLE_MS && atTarget && newFrame;
            boolean moving = settledFrames >= 0 && displayedFrames != settledFrames;
            if (settled && (!playing || moving)) {
                renderedTarget = inFlight.positionMs;
                lastRenderedPosition = inFlight.positionMs;
                inFlight = null;
            } else if (inFlight.committed && elapsed >= COMMIT_TIMEOUT_MS) {
                // Recover once at the newest requested position, without a retry loop.
                recoveryTarget = pending != null ? pending.positionMs : inFlight.positionMs;
                inFlight = null;
                pending = null;
                previewsSuspended = true;
                return null;
            } else if (!inFlight.committed && elapsed >= PREVIEW_TIMEOUT_MS) {
                previewsSuspended = true;
                if (pending != null && pending.committed) inFlight = null;
                else {
                    pending = null;
                    return null;
                }
            } else {
                if (settled && settledFrames < 0) settledFrames = displayedFrames;
                return null;
            }
        }
        if (pending == null) return null;
        if (scrubbing && pending.positionMs == lastRenderedPosition) {
            pending = null;
            return null;
        }
        inFlight = pending;
        pending = null;
        issuedAt = now;
        framesAtIssue = displayedFrames;
        settledFrames = -1;
        return inFlight;
    }

    int inFlightTarget() { return inFlight == null ? -1 : inFlight.positionMs; }

    int committedTarget() {
        if (pending != null && pending.committed) return pending.positionMs;
        if (inFlight != null && inFlight.committed) return inFlight.positionMs;
        return recoveryTarget;
    }

    int takeRecoveryTarget() {
        int result = recoveryTarget;
        recoveryTarget = -1;
        return result;
    }

    int takeRenderedTarget() {
        int result = renderedTarget;
        renderedTarget = -1;
        return result;
    }

    boolean hasWork() {
        return pending != null || recoveryTarget >= 0
                || (inFlight != null && (inFlight.committed || !previewsSuspended));
    }

    void cancelPreviews() {
        previewsSuspended = true;
        if (pending != null && !pending.committed) pending = null;
    }

    void clear() {
        pending = inFlight = null;
        recoveryTarget = -1;
        previewsSuspended = false;
        endGesture();
    }
}
