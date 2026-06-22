package com.pdfconduit.core.service;

/**
 * The canonical catalog of PDF operations — the single source of truth for an
 * operation's stable id, output-name suffix, cardinality and multi-output flag.
 * Surfaces (CLI, GUI, pipeline, a future web layer) map their own enums onto this.
 */
public enum OperationType {
    MERGE         ("merge",      "_merged",      Cardinality.REDUCE, false),
    EXTRACT       ("extract",    "_extracted",   Cardinality.MAP,    true),
    COMPRESS      ("compress",   "_compressed",  Cardinality.MAP,    false),
    ROTATE        ("rotate",     "_rotated",     Cardinality.MAP,    false),
    ARRANGE       ("arrange",    "_arranged",    Cardinality.MAP,    false),
    IMAGES_TO_PDF ("to-pdf",     "_converted",   Cardinality.MAP,    false),
    PROTECT       ("protect",    "_protected",   Cardinality.MAP,    false),
    UNLOCK        ("unlock",     "_unlocked",    Cardinality.MAP,    false),
    METADATA      ("metadata",   "_metadata",    Cardinality.MAP,    false),
    WATERMARK     ("watermark",  "_watermarked", Cardinality.MAP,    false),
    REDACT        ("redact",     "_redacted",    Cardinality.MAP,    false),
    // Terminal exports: output is not a PDF. They appear as terminal-only pipeline nodes.
    PDF_TO_IMAGES ("to-images",  "_images",      Cardinality.MAP,    true),
    PDF_TO_TEXT   ("to-text",    "_text",        Cardinality.MAP,    false);

    private final String id;
    private final String suffix;
    private final Cardinality cardinality;
    private final boolean multiOutput;

    OperationType(String id, String suffix, Cardinality cardinality, boolean multiOutput) {
        this.id = id;
        this.suffix = suffix;
        this.cardinality = cardinality;
        this.multiOutput = multiOutput;
    }

    /** Stable identifier for JSON / CLI / web. */
    public String id() { return id; }

    /** Output-name suffix, e.g. {@code _compressed}. */
    public String suffix() { return suffix; }

    public Cardinality cardinality() { return cardinality; }

    /** True when one input can yield several outputs (Extract → separate files). */
    public boolean multiOutput() { return multiOutput; }
}
