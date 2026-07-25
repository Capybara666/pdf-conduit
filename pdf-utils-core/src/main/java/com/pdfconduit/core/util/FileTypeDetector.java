package com.pdfconduit.core.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FileTypeDetector {

    /** How far into an upload a misplaced PDF header may hide before it stops counting as one. */
    private static final int PDF_SNIFF_RANGE = 1024;

    private static final byte[] PDF_HEADER = {0x25, 0x50, 0x44, 0x46, 0x2D};   // %PDF-

    private FileTypeDetector() {}

    public static boolean isPdf(Path file) {
        return matchesMagic(file, new byte[]{0x25, 0x50, 0x44, 0x46});
    }

    public static boolean isSupportedImage(Path file) {
        return isPng(file) || isJpeg(file) || isBmp(file) || isTiff(file) || isWebP(file);
    }

    // --- in-memory (byte[]) variants ---------------------------------------
    // Same magic-byte sniffing on a byte[] prefix, for the stateless web backend.

    public static boolean isPdf(byte[] data) {
        return matchesMagic(data, new byte[]{0x25, 0x50, 0x44, 0x46});
    }

    /**
     * Lenient PDF sniff for <em>damaged</em> uploads: true when the data is a PDF whose {@code %PDF-}
     * header is merely misplaced, rather than a file of some other type.
     *
     * <p>{@link #isPdf(byte[])} demands {@code %PDF} at offset 0, which is exactly one of the defects
     * the Repair operation exists to fix — a file carrying an HTTP/mail preamble before its header
     * would be refused before it could ever be repaired. The rule here, in order:
     * <ol>
     *   <li>a strict header at offset 0 — a PDF, unchanged;</li>
     *   <li>a supported image — an image, <em>never</em> a damaged PDF (checked before the loose
     *       probe, so an image carrying an embedded PDF in its metadata stays an image);</li>
     *   <li>{@code %PDF-} anywhere in the first {@value #PDF_SNIFF_RANGE} bytes — the header is
     *       there, just offset by junk. Same bounded lookup range {@code PdfRepairer} uses when it
     *       decides whether a header is misplaced or gone.</li>
     * </ol>
     *
     * <p>Deliberate limits. Data with <em>no</em> {@code %PDF-} header anywhere in that prefix is not
     * a PDF here — and cannot be one anywhere else either: PDFBox's own parser needs the header, so
     * a header-less file is unreadable by every core operation including Repair, which reports it as
     * unrecoverable. Junk longer than the prefix hides the header for good, by design: a file that
     * merely <em>contains</em> a PDF kilobytes in is not a damaged PDF. And a false positive stays
     * harmless — the data still has to survive a real PDF load, so anything that slips through fails
     * with the ordinary "cannot read / could not be repaired" message rather than being accepted.
     */
    public static boolean looksLikePdf(byte[] data) {
        if (data == null) return false;
        if (isPdf(data)) return true;
        if (isSupportedImage(data)) return false;
        return indexOf(data, PDF_HEADER, Math.min(data.length, PDF_SNIFF_RANGE)) >= 0;
    }

    public static boolean isSupportedImage(byte[] data) {
        return matchesMagic(data, new byte[]{(byte)0x89, 0x50, 0x4E, 0x47})           // PNG
            || matchesMagic(data, new byte[]{(byte)0xFF, (byte)0xD8, (byte)0xFF})     // JPEG
            || matchesMagic(data, new byte[]{0x42, 0x4D})                             // BMP
            || matchesMagic(data, new byte[]{0x49, 0x49, 0x2A, 0x00})                 // TIFF (LE)
            || matchesMagic(data, new byte[]{0x4D, 0x4D, 0x00, 0x2A})                 // TIFF (BE)
            || isWebP(data);
    }

    private static boolean isWebP(byte[] h) {
        if (h.length < 12) return false;
        return h[0]==0x52 && h[1]==0x49 && h[2]==0x46 && h[3]==0x46
            && h[8]==0x57 && h[9]==0x45 && h[10]==0x42 && h[11]==0x50;
    }

    /** First index of {@code needle} within {@code data[0, limit)}, or -1. */
    private static int indexOf(byte[] data, byte[] needle, int limit) {
        for (int i = 0; i <= limit - needle.length; i++) {
            if (startsWith(data, needle, i)) return i;
        }
        return -1;
    }

    private static boolean startsWith(byte[] data, byte[] needle, int at) {
        if (at < 0 || at + needle.length > data.length) return false;
        for (int i = 0; i < needle.length; i++) {
            if (data[at + i] != needle[i]) return false;
        }
        return true;
    }

    private static boolean matchesMagic(byte[] data, byte[] magic) {
        if (data == null || data.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) if (data[i] != magic[i]) return false;
        return true;
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
