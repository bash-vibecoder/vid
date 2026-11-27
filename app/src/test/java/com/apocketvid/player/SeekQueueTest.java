package com.apocketvid.player;

import org.junit.Test;

import static org.junit.Assert.*;

public class SeekQueueTest {
    @Test public void aChangingTargetDoesNotInterruptAnOutstandingSeek() {
        SeekQueue queue = new SeekQueue();
        queue.requestPreview(5000);
        assertEquals(5000, poll(queue, 0, 10, false).positionMs);
        queue.requestPreview(10000);
        queue.requestPreview(15000);
        assertNull(poll(queue, 200, 10, false));
        assertEquals(15000, poll(queue, 400, 11, false).positionMs);
    }

    @Test public void movingPlaybackWaitsForTwoPicturesAfterTheClockReachesTheTarget() {
        SeekQueue queue = new SeekQueue();
        queue.requestPreview(5000);
        poll(queue, 0, 10, true);
        queue.requestPreview(10000);
        assertNull(poll(queue, 48, 11, true));
        assertNull(poll(queue, 80, 12, true));
        assertEquals(10000, poll(queue, 128, 13, true).positionMs);
    }

    @Test public void releasingTheFingerReplacesPendingPreviewsWithOnePreciseSeek() {
        SeekQueue queue = new SeekQueue();
        queue.requestPreview(5000);
        poll(queue, 0, 10, false);
        queue.requestPreview(10000);
        queue.requestCommit(12000);
        assertNull(poll(queue, 100, 10, false));
        SeekQueue.Request finalSeek = poll(queue, 400, 11, false);
        assertEquals(12000, finalSeek.positionMs);
        assertTrue(finalSeek.committed);
        assertNull(poll(queue, 800, 12, false));
        assertFalse(queue.hasWork());
    }

    @Test public void aSlowPreviewSuspendsFurtherPreviewFlushesUntilRelease() {
        SeekQueue queue = new SeekQueue();
        queue.requestPreview(5000);
        poll(queue, 0, 10, true);
        queue.requestPreview(10000);
        assertNull(poll(queue, 1900, 10, true));
        assertFalse(queue.requestPreview(15000));
        assertFalse(queue.hasWork());
        queue.requestCommit(20000);
        assertTrue(poll(queue, 2000, 10, true).committed);
    }

    @Test public void aStalledFinalSeekRequestsOnlyOneRecoveryAtTheNewestTarget() {
        SeekQueue queue = new SeekQueue();
        queue.requestCommit(10000);
        poll(queue, 0, 10, true);
        queue.requestCommit(14000);
        assertNull(poll(queue, 3600, 10, true));
        assertEquals(14000, queue.takeRecoveryTarget());
        assertEquals(-1, queue.takeRecoveryTarget());
        assertFalse(queue.hasWork());
    }

    @Test public void pausingWhileASeekIsPendingDoesNotRequireContinuedMotion() {
        SeekQueue queue = new SeekQueue();
        queue.requestCommit(10000);
        poll(queue, 0, 10, true);
        assertNull(poll(queue, 400, 11, false));
        assertFalse(queue.hasWork());
    }

    @Test public void resettingNativeFrameCountersStillAcknowledgesANewPicture() {
        SeekQueue queue = new SeekQueue();
        queue.requestPreview(5000);
        poll(queue, 0, 200, false);
        queue.requestPreview(10000);
        assertEquals(10000, poll(queue, 400, 1, false).positionMs);
    }

    @Test public void committedPositionRemainsStableUntilAFrameIsRendered() {
        SeekQueue queue = new SeekQueue();
        queue.requestCommit(10000);
        assertEquals(10000, queue.committedTarget());
        poll(queue, 0, 10, false);
        assertEquals(10000, queue.committedTarget());
        poll(queue, 400, 11, false);
        assertEquals(-1, queue.committedTarget());
    }

    @Test public void clearingForANewVideoDropsOldSeekRequestsAndRecovery() {
        SeekQueue queue = new SeekQueue();
        queue.requestCommit(10000);
        poll(queue, 0, 10, false);
        queue.clear();
        assertFalse(queue.hasWork());
        assertEquals(-1, queue.committedTarget());
        assertNull(poll(queue, 4000, 10, false));
    }

    @Test public void lockingCancelsPreviewsWithoutDiscardingACommittedSeek() {
        SeekQueue queue = new SeekQueue();
        queue.requestPreview(5000);
        queue.cancelPreviews();
        assertNull(poll(queue, 0, 10, true));
        queue.requestCommit(10000);
        queue.cancelPreviews();
        assertEquals(10000, poll(queue, 100, 10, true).positionMs);
    }

    @Test public void renderedPositionIsNotPublishedBeforeAFrameArrives() {
        SeekQueue queue = new SeekQueue();
        queue.beginGesture();
        queue.requestPreview(5000);
        poll(queue, 0, 10, false);
        assertEquals(-1, queue.takeRenderedTarget());
        poll(queue, 24, 10, false);
        assertEquals(-1, queue.takeRenderedTarget());
        poll(queue, 48, 11, false);
        assertEquals(-1, queue.takeRenderedTarget());
        poll(queue, 96, 11, false);
        assertEquals(5000, queue.takeRenderedTarget());
        assertEquals(-1, queue.takeRenderedTarget());
    }

    @Test public void pausedScrubbingCanAdvanceWithoutTheOld350MillisecondWait() {
        SeekQueue queue = new SeekQueue();
        queue.beginGesture();
        queue.requestPreview(5000);
        poll(queue, 0, 10, false);
        queue.requestPreview(6000);
        assertEquals(6000, poll(queue, 96, 11, false).positionMs);
    }

    @Test public void releasingOnTheRenderedFrameDoesNotDecodeItAgain() {
        SeekQueue queue = new SeekQueue();
        queue.beginGesture();
        queue.requestPreview(5000);
        poll(queue, 0, 10, false);
        poll(queue, 96, 11, false);
        queue.takeRenderedTarget();
        queue.requestCommit(5000);
        assertFalse(queue.hasWork());
        assertEquals(5000, queue.takeRenderedTarget());
    }

    @Test public void releasingOnAnOutstandingAccuratePreviewReusesThatSeek() {
        SeekQueue queue = new SeekQueue();
        queue.beginGesture();
        queue.requestPreview(5000);
        poll(queue, 0, 10, false);
        queue.requestCommit(5000);
        assertEquals(5000, queue.committedTarget());
        assertNull(poll(queue, 96, 11, false));
        assertFalse(queue.hasWork());
    }

    @Test public void repeatingTheSamePreviewDoesNotFlushTheDecoderAgain() {
        SeekQueue queue = new SeekQueue();
        queue.beginGesture();
        queue.requestPreview(5000);
        poll(queue, 0, 10, false);
        assertFalse(queue.requestPreview(5000));
        poll(queue, 96, 11, false);
        assertFalse(queue.requestPreview(5000));
        queue.endGesture();
        queue.beginGesture();
        assertTrue(queue.requestPreview(5000));
    }

    @Test public void movingBackToTheCurrentPreviewDiscardsAnObsoletePendingSeek() {
        SeekQueue queue = new SeekQueue();
        queue.beginGesture();
        queue.requestPreview(5000);
        poll(queue, 0, 10, false);
        queue.requestPreview(6000);
        queue.requestPreview(5000);
        assertNull(poll(queue, 96, 11, false));
        assertFalse(queue.hasWork());
    }
    private SeekQueue.Request poll(SeekQueue queue, long now, int frames, boolean playing) {
        return queue.poll(now, frames, playing, Math.max(0, queue.inFlightTarget()));
    }

    @Test public void oldPlaybackFramesDoNotAdvanceTheSeekTimer() {
        SeekQueue queue = new SeekQueue();
        queue.beginGesture();
        queue.requestPreview(8000);
        queue.poll(0, 10, true, 0);
        queue.requestPreview(9000);
        assertNull(queue.poll(96, 12, true, 200));
        assertEquals(-1, queue.takeRenderedTarget());
        assertNull(queue.poll(144, 13, true, 8050));
        assertEquals(9000, queue.poll(192, 14, true, 8100).positionMs);
        assertEquals(8000, queue.takeRenderedTarget());
    }

    @Test public void aBackwardSeekWaitsForTheClockToReachItsTarget() {
        SeekQueue queue = new SeekQueue();
        queue.requestCommit(5000);
        queue.poll(0, 10, true, 15000);
        assertNull(queue.poll(96, 13, true, 15096));
        assertEquals(-1, queue.takeRenderedTarget());
        assertNull(queue.poll(144, 14, true, 5050));
        assertTrue(queue.hasWork());
        assertNull(queue.poll(192, 15, true, 5100));
        assertFalse(queue.hasWork());
        assertEquals(5000, queue.takeRenderedTarget());
    }

}
