package com.apocketvid.player;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;

final class PreviewDiskCache {
    private final File directory;
    private final long maxBytes;
    private long currentBytes = -1;

    PreviewDiskCache(File directory, long maxBytes) {
        this.directory = directory;
        this.maxBytes = maxBytes;
    }

    static String keyFor(String uri, long size, long modified, long duration) {
        String identity = "preview-v2\n" + uri + "\n" + size + "\n" + modified + "\n" + duration;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder(64);
            for (byte value : digest) {
                key.append(Character.forDigit((value & 255) >>> 4, 16));
                key.append(Character.forDigit(value & 15, 16));
            }
            return key.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    File file(String key) {
        if (!key.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid preview key");
        return new File(directory, key + ".jpg");
    }

    void put(String key, byte[] jpeg) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot save previews");
        File destination = file(key);
        if (currentBytes < 0) {
            currentBytes = 0;
            File[] saved = directory.listFiles((dir, name) -> name.endsWith(".jpg"));
            if (saved != null) for (File file : saved) currentBytes += file.length();
        }
        long oldLength = destination.length();
        File temporary = File.createTempFile("preview-", ".tmp", directory);
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(jpeg);
            }
            if (!temporary.renameTo(destination)) throw new IOException("Cannot commit preview");
            currentBytes += jpeg.length - oldLength;
        } finally {
            temporary.delete();
        }
        trim();
    }

    private void trim() {
        if (currentBytes <= maxBytes) return;
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".jpg"));
        if (files == null) return;
        long bytes = 0;
        for (File file : files) bytes += file.length();
        if (bytes <= maxBytes) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File file : files) {
            if (bytes <= maxBytes) break;
            long length = file.length();
            if (file.delete()) bytes -= length;
        }
        currentBytes = bytes;
    }
}
