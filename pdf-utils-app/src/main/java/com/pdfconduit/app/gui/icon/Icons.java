package com.pdfconduit.app.gui.icon;

import javafx.scene.Group;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Scale;

import com.pdfconduit.app.gui.sidebar.SidebarItem;
import com.pdfconduit.core.pipeline.NodeKind;

/**
 * Themeable line-art icons matching the app logo (stroke-only, rounded caps).
 *
 * <p>Each icon is a single SVG path authored in a 24×24 box. It is rendered as a
 * stroked {@link SVGPath} (no fill) whose colour comes from CSS (the
 * {@code .svg-icon} style class), so icons follow the active theme. The path is
 * scaled into a fixed-size holder so it lays out predictably inside buttons and
 * chips.
 */
public final class Icons {

    private Icons() {}

    // --- path data (24×24, stroked) --------------------------------------

    // two inputs converging into one output
    private static final String MERGE =
        "M5 7 H10 M5 17 H10 M10 7 C14 7 14 12 19 12 M10 17 C14 17 14 12 19 12";
    // scissors: cut a page range out of a document (extract)
    private static final String SPLIT =
        "M7 9 L20 17 M7 15 L20 7 "
        + "M5 9 a2 2 0 1 0 4 0 a2 2 0 1 0 -4 0 "
        + "M5 15 a2 2 0 1 0 4 0 a2 2 0 1 0 -4 0";
    // two arrows squeezing toward a middle line
    private static final String COMPRESS =
        "M4 12 H20 M12 3 V8 M9 5.5 L12 8 L15 5.5 M12 21 V16 M9 18.5 L12 16 L15 18.5";
    // clockwise circular arrow, head at the top pointing into the clockwise direction
    private static final String ROTATE =
        "M18 12 A6 6 0 1 1 12 6 M9.5 6 L12 6 L11 8.6";
    // a horizontal arrow feeding into a PDF page (convert any input "to PDF")
    private static final String TO_PDF =
        "M11 4 H17 L20 7 V20 H11 Z M17 4 V7 H20 M3 12 H9 M7 10 L9 12 L7 14";
    // a wizard hat with a sparkle (the guided wizard flow)
    private static final String WIZARD =
        "M12 3.5 L6.5 16 L17.5 16 Z M4.5 16 H19.5 "
        + "M12 6.8 L12.9 8.1 L14.2 9 L12.9 9.9 L12 11.2 L11.1 9.9 L9.8 9 L11.1 8.1 Z";
    // a small node graph: two inputs piped to one output
    private static final String PIPELINE =
        "M6.5 8 C11 8 12 12 15.5 12 M6.5 16 C11 16 12 12 15.5 12 "
        + "M4.8 8 a1.7 1.7 0 1 0 3.4 0 a1.7 1.7 0 1 0 -3.4 0 "
        + "M4.8 16 a1.7 1.7 0 1 0 3.4 0 a1.7 1.7 0 1 0 -3.4 0 "
        + "M15.3 12 a1.7 1.7 0 1 0 3.4 0 a1.7 1.7 0 1 0 -3.4 0";
    // a folder (source files)
    private static final String SOURCE =
        "M4 7 H9.5 L11.5 9 H20 V19 H4 Z";
    // reorder: an up/down arrow beside stacked rows (rearrange page order)
    private static final String ARRANGE =
        "M6 5 V19 M3.5 8 L6 5 L8.5 8 M3.5 16 L6 19 L8.5 16 "
        + "M11 7 H20 M11 12 H20 M11 17 H17";
    // a closed padlock (add password protection)
    private static final String PROTECT =
        "M6 11 H18 V20 H6 Z M8.5 11 V8 a3.5 3.5 0 0 1 7 0 V11 M12 14.5 V17";
    // an open padlock (remove password protection)
    private static final String UNLOCK =
        "M6 11 H18 V20 H6 Z M8.5 11 V8 a3.5 3.5 0 0 1 7 0 M12 14.5 V17";
    // a tag/label with a hole (document metadata)
    private static final String METADATA =
        "M4 13 L11 6 H18 V13 L11 20 Z M15 9.5 a1.1 1.1 0 1 0 0.02 0";
    // a water droplet (watermark)
    private static final String WATERMARK =
        "M12 4 C9 8 6.5 12 6.5 15 a5.5 5.5 0 0 0 11 0 C17.5 12 15 8 12 4 Z";
    // sliders: three horizontal rails each with a knob (settings)
    private static final String SETTINGS =
        "M4 7 H20 M4 12 H20 M4 17 H20 "
        + "M13 7 a2 2 0 1 0 4 0 a2 2 0 1 0 -4 0 "
        + "M7 12 a2 2 0 1 0 4 0 a2 2 0 1 0 -4 0 "
        + "M13 17 a2 2 0 1 0 4 0 a2 2 0 1 0 -4 0";

    // --- public factories -------------------------------------------------

    public static Region of(SidebarItem item, double size) {
        return icon(switch (item) {
            case MERGE    -> MERGE;
            case SPLIT    -> SPLIT;
            case COMPRESS -> COMPRESS;
            case ROTATE   -> ROTATE;
            case IMAGES   -> TO_PDF;
            case ARRANGE  -> ARRANGE;
            case PROTECT  -> PROTECT;
            case UNLOCK   -> UNLOCK;
            case METADATA -> METADATA;
            case WATERMARK -> WATERMARK;
            case PIPELINE -> PIPELINE;
            case WIZARD   -> WIZARD;
            case SETTINGS -> SETTINGS;
        }, size);
    }

    public static Region of(NodeKind kind, double size) {
        return icon(switch (kind) {
            case SOURCE        -> SOURCE;
            case MERGE         -> MERGE;
            case IMAGES_TO_PDF -> TO_PDF;
            case EXTRACT       -> SPLIT;
            case COMPRESS      -> COMPRESS;
            case ROTATE        -> ROTATE;
            case ARRANGE       -> ARRANGE;
            case PROTECT       -> PROTECT;
            case UNLOCK        -> UNLOCK;
            case METADATA      -> METADATA;
            case WATERMARK     -> WATERMARK;
        }, size);
    }

    private static Region icon(String pathData, double size) {
        SVGPath path = new SVGPath();
        path.setContent(pathData);
        path.setFill(null);                 // stroke-only; colour set via CSS
        path.getStyleClass().add("svg-icon");
        double scale = size / 24.0;
        path.getTransforms().add(new Scale(scale, scale, 0, 0));

        // Wrap in a Group so its layout bounds reflect the *scaled* ink (the Scale
        // transform is included in a Group's bounds but not in a Shape's own
        // layoutBounds); the StackPane can then centre the real ink in the box.
        Group inked = new Group(path);
        StackPane holder = new StackPane(inked);
        holder.getStyleClass().add("svg-icon-holder");
        holder.setMinSize(size, size);
        holder.setPrefSize(size, size);
        holder.setMaxSize(size, size);
        holder.setPickOnBounds(false);
        return holder;
    }
}
