package org.example.app.gui.icon;

import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Scale;

import org.example.app.gui.sidebar.SidebarItem;
import org.example.app.pipeline.NodeKind;

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
    // one input diverging into two outputs
    private static final String SPLIT =
        "M19 7 H14 M19 17 H14 M14 7 C10 7 10 12 5 12 M14 17 C10 17 10 12 5 12";
    // two arrows squeezing toward a middle line
    private static final String COMPRESS =
        "M4 12 H20 M12 3 V8 M9 5.5 L12 8 L15 5.5 M12 21 V16 M9 18.5 L12 16 L15 18.5";
    // circular refresh arrow
    private static final String ROTATE =
        "M12 6 A6 6 0 1 1 17.2 9 M12 6 L9.5 6.8 M12 6 L11.6 8.8";
    // a page with a folded corner and a down arrow (convert "to PDF")
    private static final String TO_PDF =
        "M6 3 H13 L17 7 V21 H6 Z M13 3 V7 H17 M11.5 10 V15.5 M9 13 L11.5 15.5 L14 13";
    // a magic wand with a sparkle
    private static final String WIZARD =
        "M5 19 L14.5 9.5 M13 8 L16 11 "
        + "M18.5 1.8 L19.4 3.6 L21.2 4.5 L19.4 5.4 L18.5 7.2 L17.6 5.4 L15.8 4.5 L17.6 3.6 Z";
    // a small node graph: two inputs piped to one output
    private static final String PIPELINE =
        "M6.5 8 C11 8 12 12 15.5 12 M6.5 16 C11 16 12 12 15.5 12 "
        + "M4.8 8 a1.7 1.7 0 1 0 3.4 0 a1.7 1.7 0 1 0 -3.4 0 "
        + "M4.8 16 a1.7 1.7 0 1 0 3.4 0 a1.7 1.7 0 1 0 -3.4 0 "
        + "M15.3 12 a1.7 1.7 0 1 0 3.4 0 a1.7 1.7 0 1 0 -3.4 0";
    // a folder (source files)
    private static final String SOURCE =
        "M4 7 H9.5 L11.5 9 H20 V19 H4 Z";

    // --- public factories -------------------------------------------------

    public static Region of(SidebarItem item, double size) {
        return icon(switch (item) {
            case MERGE    -> MERGE;
            case SPLIT    -> SPLIT;
            case COMPRESS -> COMPRESS;
            case ROTATE   -> ROTATE;
            case IMAGES   -> TO_PDF;
            case PIPELINE -> PIPELINE;
            case WIZARD   -> WIZARD;
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
        }, size);
    }

    private static Region icon(String pathData, double size) {
        SVGPath path = new SVGPath();
        path.setContent(pathData);
        path.setFill(null);                 // stroke-only; colour set via CSS
        path.getStyleClass().add("svg-icon");
        double scale = size / 24.0;
        path.getTransforms().add(new Scale(scale, scale, 0, 0));

        StackPane holder = new StackPane(path);
        holder.getStyleClass().add("svg-icon-holder");
        holder.setMinSize(size, size);
        holder.setPrefSize(size, size);
        holder.setMaxSize(size, size);
        holder.setPickOnBounds(false);
        return holder;
    }
}
