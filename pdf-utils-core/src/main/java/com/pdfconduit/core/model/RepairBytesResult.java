package com.pdfconduit.core.model;

import java.util.List;

/**
 * In-memory result of {@link com.pdfconduit.core.operations.PdfRepairer}: the rebuilt PDF plus an
 * honest account of what actually happened.
 *
 * <p>The two flags are deliberately independent, because "we rewrote your file" and "your file was
 * broken and is now fixed" are different claims:
 * <ul>
 *   <li>{@code wasDamaged == false} — the input was already structurally sound. It was still
 *       rewritten (fresh cross-reference table, unreachable objects dropped), but nothing was
 *       <em>repaired</em>; {@code recovered} is then always {@code false}.</li>
 *   <li>{@code wasDamaged == true, recovered == true} — the input did not parse under strict rules,
 *       and the rewritten output does. That is a verified fix, not a hope.</li>
 *   <li>{@code wasDamaged == true, recovered == false} — the file was damaged and the rebuilt
 *       output <em>still</em> fails a strict parse. The best result we could produce is returned,
 *       carrying {@link RepairFinding#REBUILD_INCOMPLETE}. Not every file can be recovered.</li>
 * </ul>
 *
 * @param bytes         the rebuilt PDF
 * @param wasDamaged    whether the input failed a strict (non-lenient) parse or showed a concrete
 *                      structural defect
 * @param recovered     whether a damaged input was rebuilt into a strictly-parseable PDF
 * @param pageCount     pages in the rebuilt document
 * @param originalBytes size of the input, in bytes
 * @param resultBytes   size of {@link #bytes}, in bytes
 * @param findings      the concrete defects detected (empty for an already-sound file)
 */
public record RepairBytesResult(byte[] bytes, boolean wasDamaged, boolean recovered, int pageCount,
                                long originalBytes, long resultBytes, List<RepairFinding> findings) {

    public RepairBytesResult {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }
}
