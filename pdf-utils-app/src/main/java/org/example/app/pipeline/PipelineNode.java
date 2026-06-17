package org.example.app.pipeline;

import org.example.core.model.PageSize;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A node in the pipeline graph. Mutable — the GUI edits its config and position
 * in place. Only the fields relevant to {@link #kind} are used.
 */
public class PipelineNode {

    public final String id;
    public final NodeKind kind;
    public double x;
    public double y;

    // SOURCE
    public final List<Path> files = new ArrayList<>();

    // EXTRACT / ROTATE
    public String pages = "";

    // ROTATE
    public int angle = 90;

    // COMPRESS
    public long targetBytes = 5L * 1024 * 1024;

    // IMAGES_TO_PDF
    public PageSize pageSize = PageSize.FIT;

    // terminal nodes only — a file path (single output) or folder (multiple)
    public String outputDestination = "";

    public PipelineNode(String id, NodeKind kind, double x, double y) {
        this.id = id;
        this.kind = kind;
        this.x = x;
        this.y = y;
    }
}
