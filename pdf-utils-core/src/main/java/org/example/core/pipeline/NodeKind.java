package org.example.core.pipeline;

/** The kinds of node a pipeline can contain. */
public enum NodeKind {
    SOURCE("Files", ""),
    MERGE("Merge", "_merged"),
    IMAGES_TO_PDF("To PDF", "_converted"),
    EXTRACT("Extract", "_extracted"),
    COMPRESS("Compress", "_compressed"),
    ROTATE("Rotate", "_rotated"),
    ARRANGE("Arrange", "_arranged"),
    PROTECT("Protect", "_protected"),
    UNLOCK("Unlock", "_unlocked"),
    METADATA("Metadata", "_metadata"),
    WATERMARK("Watermark", "_watermarked");

    public final String label;
    public final String suffix;

    NodeKind(String label, String suffix) {
        this.label = label;
        this.suffix = suffix;
    }

    public boolean isSource()  { return this == SOURCE; }

    /** Reduce ops collapse a whole input bundle into a single output document. */
    public boolean isReduce()  { return this == MERGE; }

    /**
     * Map ops apply once per input document (bundle in → same-size bundle out).
     * TO PDF converts each input to its own PDF — combining requires an explicit
     * MERGE node.
     */
    public boolean isMap()     {
        return this == EXTRACT || this == COMPRESS || this == ROTATE
            || this == ARRANGE || this == IMAGES_TO_PDF
            || this == PROTECT || this == UNLOCK || this == METADATA
            || this == WATERMARK;
    }
}
