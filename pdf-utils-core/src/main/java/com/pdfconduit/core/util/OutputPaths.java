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
}
