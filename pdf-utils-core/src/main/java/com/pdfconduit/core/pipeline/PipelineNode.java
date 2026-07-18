package com.pdfconduit.core.pipeline;

import com.pdfconduit.core.model.PageSize;
import com.pdfconduit.core.model.SplitMode;

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

    // EXTRACT — combine selected pages into one PDF, or split into one file per page
    public SplitMode splitMode = SplitMode.COMBINE;

    // ARRANGE — page order expression, e.g. "3,1,2" (blank = keep natural order)
    public String order = "";

    // ROTATE
    public int angle = 90;

    // COMPRESS
    public long targetBytes = 5L * 1024 * 1024;

    // IMAGES_TO_PDF
    public PageSize pageSize = PageSize.FIT;

    // PROTECT / UNLOCK — password to set (protect) or to open with (unlock);
    // ownerPassword (protect only) defaults to the user password when blank.
    public String password = "";
    public String ownerPassword = "";

    // METADATA — set the non-blank fields (blank = leave unchanged); metaStrip
    // removes all metadata and ignores the fields.
    public String metaTitle = "";
    public String metaAuthor = "";
    public String metaSubject = "";
    public String metaKeywords = "";
    public boolean metaStrip = false;

    // WATERMARK — text or image path (image wins if both set); opacity 0–1, rotation in degrees.
    public String wmText = "";
    public String wmImage = "";
    public double wmOpacity = 0.3;
    public double wmRotation = 45;
    public double wmScale = 0.7;

    // CROP — trim a margin off each page edge; values are in points, or millimetres when cropMm.
    public double cropTop = 0;
    public double cropRight = 0;
    public double cropBottom = 0;
    public double cropLeft = 0;
    public boolean cropMm = false;

    // TO_IMAGES export
    public com.pdfconduit.core.model.ImageFormat imageFormat = com.pdfconduit.core.model.ImageFormat.PNG;
    public int imageDpi = 150;
    public float jpegQuality = 0.85f;

    // TO_TEXT export
    public com.pdfconduit.core.model.TextFormat textFormat = com.pdfconduit.core.model.TextFormat.TXT;

    // terminal nodes only — a file path (single output) or folder (multiple)
    public String outputDestination = "";

    public PipelineNode(String id, NodeKind kind, double x, double y) {
        this.id = id;
        this.kind = kind;
        this.x = x;
        this.y = y;
    }
}
