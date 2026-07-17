package com.pdfconduit.web.dto;

import com.pdfconduit.core.pipeline.NodeKind;

/**
 * UI catalog entry describing one pipeline {@link NodeKind}, for the Angular builder palette.
 *
 * @param name     the stable enum name (matches the JSON serialisation of a node's {@code kind})
 * @param label    human-readable label
 * @param isSource whether this is the file-source node
 * @param isReduce whether it collapses a bundle into one output (Merge)
 * @param isExport whether it is a terminal export (To Images / To Text — output is not a PDF)
 */
public record NodeKindInfo(String name, String label, boolean isSource, boolean isReduce, boolean isExport) {

    public static NodeKindInfo of(NodeKind kind) {
        return new NodeKindInfo(kind.name(), kind.label, kind.isSource(), kind.isReduce(), kind.isExport());
    }
}
