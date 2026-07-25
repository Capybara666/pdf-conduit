package com.pdfconduit.core.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

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

    // --- lenient sniff for damaged uploads ---------------------------------

    private static byte[] withPrefix(int junkBytes, String tail) {
        byte[] t = tail.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[junkBytes + t.length];
        Arrays.fill(out, 0, junkBytes, (byte) 'X');
        System.arraycopy(t, 0, out, junkBytes, t.length);
        return out;
    }

    @Test
    void looksLikePdfAcceptsAHeaderAtOffsetZero() {
        assertTrue(FileTypeDetector.looksLikePdf("%PDF-1.7\n1 0 obj\n".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void looksLikePdfAcceptsAHeaderPushedBackByJunk() {
        assertTrue(FileTypeDetector.looksLikePdf(withPrefix(200, "%PDF-1.4\n1 0 obj\n")));
        assertTrue(FileTypeDetector.looksLikePdf(withPrefix(1000, "%PDF-1.4\n1 0 obj\n")));
    }

    @Test
    void looksLikePdfIsBounded() {
        // The header exists, but far past the sniff window: not a damaged PDF, just a file that
        // happens to contain one.
        assertFalse(FileTypeDetector.looksLikePdf(withPrefix(4096, "%PDF-1.4\n1 0 obj\n")));
    }

    @Test
    void looksLikePdfRejectsNonPdfData() {
        assertFalse(FileTypeDetector.looksLikePdf(null));
        assertFalse(FileTypeDetector.looksLikePdf(new byte[0]));
        assertFalse(FileTypeDetector.looksLikePdf("just some prose about files".getBytes(StandardCharsets.US_ASCII)));
        assertFalse(FileTypeDetector.looksLikePdf(new byte[]{'P', 'K', 0x03, 0x04, 0x14, 0x00}));   // zip/docx
    }

    @Test
    void looksLikePdfNeverReclassifiesAnImage() {
        byte[] png = new byte[64];
        png[0] = (byte) 0x89; png[1] = 0x50; png[2] = 0x4E; png[3] = 0x47;
        byte[] marker = "%PDF-1.4".getBytes(StandardCharsets.US_ASCII);   // e.g. embedded metadata
        System.arraycopy(marker, 0, png, 32, marker.length);

        assertTrue(FileTypeDetector.isSupportedImage(png));
        assertFalse(FileTypeDetector.looksLikePdf(png), "an image stays an image");
    }
}
