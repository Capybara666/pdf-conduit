package com.pdfconduit.web.support;

import com.pdfconduit.core.service.NamedBytes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds an in-memory ZIP of {@link NamedBytes} results — no temp files, entries de-duplicated. */
public final class Zips {

    private Zips() {}

    /** Zips {@code entries} (each named by its {@link NamedBytes#filename()}, de-duplicated). */
    public static byte[] zip(List<NamedBytes> entries) {
        // Size the buffer up front from the entries themselves. Already-compressed payloads (PNG,
        // JPEG, PDF object streams) barely shrink, so the default 32-byte buffer would double its
        // way there, and each doubling holds the old AND the new array at once — a needless second
        // full copy of a result that is already the largest thing in the heap.
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(estimatedSize(entries));
        Set<String> used = new HashSet<>();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (NamedBytes e : entries) {
                zip.putNextEntry(new ZipEntry(uniqueEntry(used, sanitize(e.filename()))));
                zip.write(e.data());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to build ZIP", e);
        }
        return buffer.toByteArray();
    }

    /** Summed entry size + a small per-entry allowance for the ZIP headers (clamped to int). */
    private static int estimatedSize(List<NamedBytes> entries) {
        long total = 64;
        for (NamedBytes e : entries) total += e.data().length + 128L;
        return (int) Math.min(Integer.MAX_VALUE - 8L, total);
    }

    /**
     * Reduces an entry name to a safe basename: any path is stripped ({@code /} and {@code \}), and
     * any residual {@code ..} segment neutralised — so a crafted upload filename cannot write
     * outside the archive root when the ZIP is later extracted (zip-slip).
     */
    static String sanitize(String name) {
        if (name == null || name.isBlank()) return "file";
        String n = name.replace('\\', '/');
        int slash = n.lastIndexOf('/');
        if (slash >= 0) n = n.substring(slash + 1);
        n = n.strip();
        // Drop leading dots so "..", "..." collapse to a harmless name; keep normal dotted names.
        while (n.startsWith(".")) n = n.substring(1);
        return n.isBlank() ? "file" : n;
    }

    private static String uniqueEntry(Set<String> used, String name) {
        if (used.add(name)) return name;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; ; i++) {
            String candidate = stem + "_" + i + ext;
            if (used.add(candidate)) return candidate;
        }
    }
}
