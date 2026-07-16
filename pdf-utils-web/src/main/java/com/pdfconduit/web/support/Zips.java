package com.pdfconduit.web.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds an in-memory ZIP of result files, with an optional {@code _failures.txt} entry. */
public final class Zips {

    private Zips() {}

    /**
     * Zips {@code files} (each entry named by its filename, de-duplicated) plus, when
     * {@code failuresText} is non-blank, a {@code _failures.txt} entry. Returns the ZIP bytes.
     */
    public static byte[] zip(List<Path> files, String failuresText) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Set<String> used = new HashSet<>();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (Path file : files) {
                String entry = uniqueEntry(used, file.getFileName().toString());
                zip.putNextEntry(new ZipEntry(entry));
                Files.copy(file, zip);
                zip.closeEntry();
            }
            if (failuresText != null && !failuresText.isBlank()) {
                zip.putNextEntry(new ZipEntry("_failures.txt"));
                zip.write(failuresText.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to build ZIP", e);
        }
        return buffer.toByteArray();
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
