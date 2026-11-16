package com.apocketvid.player;

import java.util.HashSet;
import java.util.Set;

/** Limits retries after native decoder failures without blocking cached previews. */
final class PreviewFailures {
    private final Set<String> failed = new HashSet<>();
    private int crashes;

    boolean canExtract(String key) { return crashes < 3 && !failed.contains(key); }
    void failed(String key, boolean processCrashed) {
        failed.add(key);
        if (processCrashed) crashes++;
    }
    void recovered(String key) { failed.remove(key); }
}
