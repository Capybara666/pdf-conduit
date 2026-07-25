package com.pdfconduit.core.model;

import java.nio.file.Path;
import java.util.List;

/**
 * {@code Path}-based result of {@link com.pdfconduit.core.operations.PdfRepairer} — the same honest
 * report as {@link RepairBytesResult}, with the written file instead of its bytes. See
 * {@link RepairBytesResult} for the exact meaning of {@code wasDamaged} vs {@code recovered}.
 *
 * @param output        the rebuilt PDF on disk
 * @param wasDamaged    whether the input failed a strict (non-lenient) parse or showed a concrete
 *                      structural defect
 * @param recovered     whether a damaged input was rebuilt into a strictly-parseable PDF
 * @param pageCount     pages in the rebuilt document
 * @param originalBytes size of the input, in bytes
 * @param resultBytes   size of the output, in bytes
 * @param findings      the concrete defects detected (empty for an already-sound file)
 */
public record RepairResult(Path output, boolean wasDamaged, boolean recovered, int pageCount,
                           long originalBytes, long resultBytes, List<RepairFinding> findings) {

    public RepairResult {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }
}
