package com.pdfconduit.core.model;

import java.nio.file.Path;

/**
 * Select {@code pages} from {@code input} and write them according to {@code mode}:
 * {@link SplitMode#COMBINE} treats {@code output} as a single PDF file;
 * {@link SplitMode#SEPARATE} treats it as a folder and writes one PDF per chunk of
 * {@code pagesPerChunk} selected pages (the default, 1, being one PDF per page).
 *
 * <p>{@code pagesPerChunk} is the "split every N pages" knob: the selected pages are cut into
 * consecutive groups of N in selection order, so the last group may be shorter and an N at or
 * above the selection size yields a single output. It is meaningless for
 * {@link SplitMode#COMBINE} (everything already lands in one file) and is ignored there.
 *
 * @param pagesPerChunk how many selected pages go into each output file; must be {@code >= 1}
 */
public record SplitOptions(Path input, PageRange pages, SplitMode mode, Path output,
                           int pagesPerChunk) {

    public SplitOptions {
        if (pagesPerChunk < 1) {
            throw new IllegalArgumentException("Pages per file must be at least 1.");
        }
    }

    /** Convenience for the default one-page-per-output case. */
    public SplitOptions(Path input, PageRange pages, SplitMode mode, Path output) {
        this(input, pages, mode, output, 1);
    }

    /** Convenience for the common combine-into-one-file case. */
    public SplitOptions(Path input, PageRange pages, Path output) {
        this(input, pages, SplitMode.COMBINE, output);
    }
}
