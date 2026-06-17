package org.example.app.pipeline;

/** The kinds of node a pipeline can contain. */
public enum NodeKind {
    SOURCE("Files", ""),
    MERGE("Merge", "_merged"),
    IMAGES_TO_PDF("Images → PDF", "_converted"),
    EXTRACT("Extract", "_extracted"),
    COMPRESS("Compress", "_compressed"),
    ROTATE("Rotate", "_rotated");

    public final String label;
    public final String suffix;

    NodeKind(String label, String suffix) {
        this.label = label;
        this.suffix = suffix;
    }

    public boolean isSource()  { return this == SOURCE; }

    /** Reduce ops collapse a whole input bundle into a single output document. */
    public boolean isReduce()  { return this == MERGE || this == IMAGES_TO_PDF; }

    /** Map ops apply once per input document (bundle in → same-size bundle out). */
    public boolean isMap()     { return this == EXTRACT || this == COMPRESS || this == ROTATE; }
}
