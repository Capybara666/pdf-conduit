package org.example.core.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class FileTypeDetector {

    public static boolean isPdf(Path file) {
        return matchesMagic(file, new byte[]{0x25, 0x50, 0x44, 0x46});
    }

    public static boolean isSupportedImage(Path file) {
        return isPng(file) || isJpeg(file) || isBmp(file) || isTiff(file) || isWebP(file);
    }

    private static boolean isPng(Path f) {
        return matchesMagic(f, new byte[]{(byte)0x89, 0x50, 0x4E, 0x47});
    }
    private static boolean isJpeg(Path f) {
        return matchesMagic(f, new byte[]{(byte)0xFF, (byte)0xD8, (byte)0xFF});
    }
    private static boolean isBmp(Path f) {
        return matchesMagic(f, new byte[]{0x42, 0x4D});
    }
    private static boolean isTiff(Path f) {
        return matchesMagic(f, new byte[]{0x49, 0x49, 0x2A, 0x00})
            || matchesMagic(f, new byte[]{0x4D, 0x4D, 0x00, 0x2A});
    }
    private static boolean isWebP(Path f) {
        try (InputStream is = Files.newInputStream(f)) {
            byte[] h = is.readNBytes(12);
            if (h.length < 12) return false;
            return h[0]==0x52 && h[1]==0x49 && h[2]==0x46 && h[3]==0x46
                && h[8]==0x57 && h[9]==0x45 && h[10]==0x42 && h[11]==0x50;
        } catch (IOException e) { return false; }
    }
    private static boolean matchesMagic(Path file, byte[] magic) {
        try (InputStream is = Files.newInputStream(file)) {
            byte[] h = is.readNBytes(magic.length);
            if (h.length < magic.length) return false;
            for (int i = 0; i < magic.length; i++) if (h[i] != magic[i]) return false;
            return true;
        } catch (IOException e) { return false; }
    }
}
