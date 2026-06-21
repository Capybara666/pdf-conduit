package com.pdfconduit.core.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Utilities for preparing output file locations before writing. */
public final class OutputPaths {

    private OutputPaths() {}

    /**
     * Ensures the parent directory of {@code output} exists, creating it (and any
     * missing ancestors) if necessary. No-op when {@code output} has no parent.
     */
    public static void ensureParentDir(Path output) throws IOException {
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    /**
     * Returns {@code desired} if nothing exists there, otherwise the first free
     * variant with a {@code " (n)"} suffix inserted before the extension — e.g.
     * {@code report.pdf} → {@code report (1).pdf} → {@code report (2).pdf}. Used to
     * offer a non-clobbering name when the chosen output already exists.
     */
    public static Path uniquePath(Path desired) {
        if (!Files.exists(desired)) return desired;
        Path parent = desired.getParent();
        String name = desired.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; ; i++) {
            String candidate = stem + " (" + i + ")" + ext;
            Path path = parent == null ? Path.of(candidate) : parent.resolve(candidate);
            if (!Files.exists(path)) return path;
        }
    }
}
