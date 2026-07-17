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
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Set<String> used = new HashSet<>();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (NamedBytes e : entries) {
                zip.putNextEntry(new ZipEntry(uniqueEntry(used, e.filename())));
                zip.write(e.data());
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
