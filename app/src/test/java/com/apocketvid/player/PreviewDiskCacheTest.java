package com.apocketvid.player;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class PreviewDiskCacheTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void changingAFileInvalidatesItsPreview() {
        String original = PreviewDiskCache.keyFor("content://videos/1", 100, 20, 3000);
        assertEquals(original, PreviewDiskCache.keyFor("content://videos/1", 100, 20, 3000));
        assertNotEquals(original, PreviewDiskCache.keyFor("content://videos/1", 101, 20, 3000));
        assertNotEquals(original, PreviewDiskCache.keyFor("content://videos/1", 100, 21, 3000));
        assertNotEquals(original, PreviewDiskCache.keyFor("content://videos/2", 100, 20, 3000));
        assertTrue(original.matches("[a-f0-9]{64}"));
    }

    @Test public void previewSurvivesReopeningTheCache() throws Exception {
        File directory = temporary.newFolder();
        String key = PreviewDiskCache.keyFor("content://videos/1", 100, 20, 3000);
        PreviewDiskCache first = new PreviewDiskCache(directory, 100);
        first.put(key, new byte[] {1, 2, 3});
        PreviewDiskCache reopened = new PreviewDiskCache(directory, 100);
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(reopened.file(key).toPath()));
        first.put(key, new byte[] {4, 5});
        assertArrayEquals(new byte[] {4, 5}, Files.readAllBytes(reopened.file(key).toPath()));
        assertEquals(1, directory.list().length);
    }

    @Test public void cacheEvictsOldestPreviewWhenFull() throws Exception {
        PreviewDiskCache cache = new PreviewDiskCache(temporary.newFolder(), 5);
        String oldKey = PreviewDiskCache.keyFor("old", 1, 1, 1);
        String newKey = PreviewDiskCache.keyFor("new", 1, 1, 1);
        cache.put(oldKey, new byte[] {1, 2, 3});
        assertTrue(cache.file(oldKey).setLastModified(1000));
        cache.put(newKey, new byte[] {4, 5, 6});
        assertFalse(cache.file(oldKey).exists());
        assertTrue(cache.file(newKey).exists());
    }
}
