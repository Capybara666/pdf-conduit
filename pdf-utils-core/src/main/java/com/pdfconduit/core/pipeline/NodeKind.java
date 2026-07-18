package com.pdfconduit.core.pipeline;

import com.pdfconduit.core.service.Cardinality;
import com.pdfconduit.core.service.OperationType;

/** The kinds of node a pipeline can contain. Operation metadata is delegated to {@link OperationType}. */
public enum NodeKind {
    SOURCE("Files", null),
    MERGE("Merge", OperationType.MERGE),
    IMAGES_TO_PDF("To PDF", OperationType.IMAGES_TO_PDF),
    EXTRACT("Extract", OperationType.EXTRACT),
    COMPRESS("Compress", OperationType.COMPRESS),
    ROTATE("Rotate", OperationType.ROTATE),
    ARRANGE("Arrange", OperationType.ARRANGE),
    PROTECT("Protect", OperationType.PROTECT),
    UNLOCK("Unlock", OperationType.UNLOCK),
    METADATA("Metadata", OperationType.METADATA),
    WATERMARK("Watermark", OperationType.WATERMARK),
    CROP("Crop", OperationType.CROP),
    NUP("N-up", OperationType.NUP),
    PAGE_MARKS("Page Marks", OperationType.PAGE_MARKS),
    // Terminal exports — output is not a PDF, so they cannot feed downstream nodes.
    TO_IMAGES("To Images", OperationType.PDF_TO_IMAGES),
    TO_TEXT("To Text", OperationType.PDF_TO_TEXT);

    public final String label;
    private final OperationType type;

    NodeKind(String label, OperationType type) {
        this.label = label;
        this.type = type;
    }

    /** The catalog entry for this node, or {@code null} for {@link #SOURCE}. */
    public OperationType operationType() { return type; }

    /** Output-name suffix; empty for {@link #SOURCE}. */
    public String suffix() { return type == null ? "" : type.suffix(); }

    public boolean isSource() { return this == SOURCE; }

    /** Export sinks (PDF→images, PDF→text): their output is not a PDF, so they must be terminal. */
    public boolean isExport() { return this == TO_IMAGES || this == TO_TEXT; }

    /** Reduce ops collapse a whole input bundle into a single output document. */
    public boolean isReduce() { return type != null && type.cardinality() == Cardinality.REDUCE; }

    /** Map ops apply once per input document (bundle in → same-size bundle out). */
    public boolean isMap() { return type != null && type.cardinality() == Cardinality.MAP; }
}
