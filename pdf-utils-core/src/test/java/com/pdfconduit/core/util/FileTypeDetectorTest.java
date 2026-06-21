package com.pdfconduit.core.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileTypeDetectorTest {

    @TempDir Path tmp;

    @Test
    void detectsPdf() throws IOException {
        Path f = tmp.resolve("test.pdf");
        Files.write(f, new byte[]{0x25, 0x50, 0x44, 0x46, 0x2D, 0x31});
        assertTrue(FileTypeDetector.isPdf(f));
        assertFalse(FileTypeDetector.isSupportedImage(f));
    }

    @Test
    void detectsPng() throws IOException {
        Path f = tmp.resolve("test.png");
        Files.write(f, new byte[]{(byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
        assertFalse(FileTypeDetector.isPdf(f));
        assertTrue(FileTypeDetector.isSupportedImage(f));
    }

    @Test
    void detectsJpeg() throws IOException {
        Path f = tmp.resolve("test.jpg");
        Files.write(f, new byte[]{(byte)0xFF, (byte)0xD8, (byte)0xFF, (byte)0xE0});
        assertTrue(FileTypeDetector.isSupportedImage(f));
    }

    @Test
    void unknownFileReturnsFalse() throws IOException {
        Path f = tmp.resolve("test.xyz");
        Files.write(f, new byte[]{0x00, 0x01, 0x02});
        assertFalse(FileTypeDetector.isPdf(f));
        assertFalse(FileTypeDetector.isSupportedImage(f));
    }
}
