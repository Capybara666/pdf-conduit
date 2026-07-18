package com.pdfconduit.core.model;

import java.nio.file.Path;

/**
 * Options for N-up / booklet imposition. When {@code booklet} is true the pages are
 * imposed as a saddle-stitch booklet (2-up landscape with the page order reordered
 * for folding) and {@code layout} is ignored; otherwise the {@code layout} grid preset
 * places {@code layout.perSheet()} source pages onto each output sheet.
 */
public record NupOptions(Path input, NupLayout layout, boolean booklet, Path output) {

    public NupOptions {
        if (layout == null) layout = NupLayout.TWO_UP;
    }
}
