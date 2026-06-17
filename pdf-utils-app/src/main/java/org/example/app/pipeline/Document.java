package org.example.app.pipeline;

import java.nio.file.Path;

/**
 * A single document flowing through a pipeline. Bundles (ordered lists of these)
 * travel along the graph's edges.
 *
 * @param file     the file on disk (a source file, or a temp/destination output)
 * @param type     PDF or IMAGE — known statically (source extension; op outputs are PDF)
 * @param baseName name stem used when naming outputs (no extension)
 */
public record Document(Path file, DocType type, String baseName) {

    public enum DocType { PDF, IMAGE }

    public static DocType typeOf(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".pdf") ? DocType.PDF : DocType.IMAGE;
    }

    public static String stemOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }
}
