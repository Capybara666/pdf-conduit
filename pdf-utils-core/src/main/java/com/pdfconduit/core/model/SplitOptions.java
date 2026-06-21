package com.pdfconduit.core.model;

import java.nio.file.Path;

/**
 * Select {@code pages} from {@code input} and write them according to {@code mode}:
 * {@link SplitMode#COMBINE} treats {@code output} as a single PDF file;
 * {@link SplitMode#SEPARATE} treats it as a folder and writes one PDF per page.
 */
public record SplitOptions(Path input, PageRange pages, SplitMode mode, Path output) {

    /** Convenience for the common combine-into-one-file case. */
    public SplitOptions(Path input, PageRange pages, Path output) {
        this(input, pages, SplitMode.COMBINE, output);
    }
}
