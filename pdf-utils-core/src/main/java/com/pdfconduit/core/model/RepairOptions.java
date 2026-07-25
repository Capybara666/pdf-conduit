package com.pdfconduit.core.model;

import java.nio.file.Path;

/**
 * Options for {@link com.pdfconduit.core.operations.PdfRepairer}.
 *
 * <p>Unlike every other operation, Repair deliberately takes its input <em>as-is</em> — it is never
 * pre-converted or re-encoded, because the damage being repaired lives in the file's own byte
 * structure.
 *
 * @param input  the (possibly damaged) PDF to rebuild
 * @param output where the rebuilt PDF is written
 */
public record RepairOptions(Path input, Path output) {}
