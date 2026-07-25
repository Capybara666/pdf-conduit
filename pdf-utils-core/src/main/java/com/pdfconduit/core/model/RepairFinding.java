package com.pdfconduit.core.model;

/**
 * A concrete structural defect {@link com.pdfconduit.core.operations.PdfRepairer} found in the
 * input (and, except for {@link #REBUILD_INCOMPLETE}, addressed by rewriting the file).
 *
 * <p>These are the honest, checkable facts behind a repair — not marketing. Each value corresponds
 * to something actually measured on the input bytes or observed while parsing, so a caller can
 * tell the user <em>what</em> was wrong instead of just "repaired".
 */
public enum RepairFinding {

    /** No {@code %PDF-} header anywhere in the first kilobyte — the file barely identifies as a PDF. */
    HEADER_MISSING("header-missing"),

    /** The {@code %PDF-} header exists but not at offset 0: junk (mail/HTTP preamble) precedes it. */
    HEADER_OFFSET("header-offset"),

    /** No {@code %%EOF} marker near the end — the file was truncated or the tail was overwritten. */
    EOF_MISSING("eof-missing"),

    /** No {@code startxref} keyword: nothing points at the cross-reference table. */
    STARTXREF_MISSING("startxref-missing"),

    /** {@code startxref} exists but its offset is out of range or points at neither an xref nor an object. */
    STARTXREF_INVALID("startxref-invalid"),

    /**
     * The cross-reference data could not be read as written, so it was reconstructed by scanning the
     * file for objects (PDFBox's brute-force recovery) and a fresh, correct table was written out.
     */
    XREF_REBUILT("xref-rebuilt"),

    /**
     * The rewritten file <em>still</em> does not parse under strict rules. The best-effort result is
     * returned anyway, but the repair is reported as not fully successful.
     */
    REBUILD_INCOMPLETE("rebuild-incomplete");

    private final String id;

    RepairFinding(String id) {
        this.id = id;
    }

    /** Stable, lower-case identifier for JSON / CLI / web output. */
    public String id() {
        return id;
    }
}
