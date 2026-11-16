package com.apocketvid.player;

import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import java.nio.ByteBuffer;

/** Only PreviewService loads this bundled FFmpeg decoder. Android supplies file access and bitmaps. */
final class NativePreview {
    static {
        for (String library : new String[] {"crypto", "ssl", "avutil", "swscale", "avcodec", "avformat", "apocketvid_preview"}) {
            System.loadLibrary(library);
        }
    }

    static Bitmap frame(AssetFileDescriptor source, long timeUs) {
        int[] info = new int[3];
        byte[] pixels = readFrame(source.getParcelFileDescriptor().getFd(), source.getStartOffset(),
                source.getDeclaredLength(), timeUs, PreviewProtocol.MAX_EDGE, info);
        if (pixels == null || info[0] < 1 || info[1] < 1 || pixels.length != info[0] * info[1] * 4) return null;
        Bitmap bitmap = Bitmap.createBitmap(info[0], info[1], Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels));
        if (info[2] != 0) {
            Matrix rotation = new Matrix();
            rotation.postRotate(info[2]);
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), rotation, false);
            if (rotated != bitmap) bitmap.recycle();
            bitmap = rotated;
        }
        return bitmap;
    }

    private static native byte[] readFrame(int fd, long offset, long length, long timeUs, int maxEdge, int[] dimensions);
    private NativePreview() {}
}
