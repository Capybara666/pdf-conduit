package com.pdfconduit.app.gui.pipeline;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import com.pdfconduit.app.gui.component.ProgressPanel;
import com.pdfconduit.app.i18n.I18n;
import com.pdfconduit.core.pipeline.PipelineNode;

/** Visual card for a {@link PipelineNode}: header (drag/close), summary, ports. */
class NodeView extends HBox {

    final PipelineNode node;
    private final Circle inPort;
    private final Circle outPort;
    private final Label title = new Label();
    private final Label summary = new Label();
    private final VBox card;

    NodeView(PipelineNode node, PipelineCanvas canvas) {
        this.node = node;
        setLayoutX(node.x);
        setLayoutY(node.y);
        setAlignment(Pos.CENTER);
        getStyleClass().add("pipeline-node-wrap");

        inPort = makePort("pipeline-port-in");
        outPort = makePort("pipeline-port-out");
        VBox leftCol = new VBox(inPort);
        leftCol.setAlignment(Pos.CENTER);
        VBox rightCol = new VBox(outPort);
        rightCol.setAlignment(Pos.CENTER);

        Region titleIcon = com.pdfconduit.app.gui.icon.Icons.of(node.kind, 14);
        titleIcon.getStyleClass().add("pipeline-node-title-icon");
        title.setText(I18n.t("kind." + node.kind.name()));
        title.getStyleClass().add("pipeline-node-title");
        Button close = new Button("✕");
        close.getStyleClass().add("pipeline-node-close");
        close.setOnAction(e -> canvas.removeNode(node));
        // Hovering the ✕ softly highlights the whole block it would remove.
        close.setOnMouseEntered(e -> toggle("remove-hint", true));
        close.setOnMouseExited(e -> toggle("remove-hint", false));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(6, titleIcon, title, spacer, close);
        header.getStyleClass().add("pipeline-node-header");
        header.setAlignment(Pos.CENTER_LEFT);

        summary.getStyleClass().add("pipeline-node-summary");
        summary.setWrapText(true);

        card = new VBox(4, header, summary);
        card.getStyleClass().add("pipeline-node");

        if (node.kind.isSource()) {
            getChildren().addAll(card, rightCol);
        } else {
            getChildren().addAll(leftCol, card, rightCol);
        }

        // Drag the node by its header.
        final double[] off = new double[2];
        header.setOnMousePressed(e -> {
            off[0] = e.getSceneX() - getLayoutX();
            off[1] = e.getSceneY() - getLayoutY();
            canvas.selectNode(node);
            e.consume();
        });
        header.setOnMouseDragged(e -> {
            setLayoutX(Math.max(0, e.getSceneX() - off[0]));
            setLayoutY(Math.max(0, e.getSceneY() - off[1]));
            node.x = getLayoutX();
            node.y = getLayoutY();
            e.consume();
        });

        card.setOnMousePressed(e -> canvas.selectNode(node));

        // Connection gesture: a plain press-drag from the output port. All drag
        // and release events are delivered to the source port, so on release the
        // canvas hit-tests which input port is under the cursor. No full-drag /
        // target handlers, so nothing fights the ScrollPane.
        outPort.setOnDragDetected(e -> { canvas.beginConnect(node); e.consume(); });
        outPort.setOnMouseDragged(e -> {
            canvas.updateTempEnd(e.getSceneX(), e.getSceneY());
            e.consume();
        });
        outPort.setOnMouseReleased(e -> {
            canvas.finishConnectAt(e.getSceneX(), e.getSceneY());
            e.consume();
        });

        refreshSummary();
    }

    private Circle makePort(String styleClass) {
        Circle c = new Circle(6);
        c.getStyleClass().addAll("pipeline-port", styleClass);
        return c;
    }

    Circle inPort()  { return inPort; }
    Circle outPort() { return outPort; }

    void setSelected(boolean selected) {
        toggle("selected", selected);
    }

    void setError(boolean error) {
        toggle("error", error);
    }

    /** Highlights the input port to signal a connection that would be rejected. */
    void setInPortInvalid(boolean invalid) {
        togglePortClass("pipeline-port-invalid", invalid);
    }

    /** Highlights the input port to signal a connection that is allowed. */
    void setInPortValid(boolean valid) {
        togglePortClass("pipeline-port-valid", valid);
    }

    private void togglePortClass(String styleClass, boolean on) {
        if (on) {
            if (!inPort.getStyleClass().contains(styleClass))
                inPort.getStyleClass().add(styleClass);
        } else {
            inPort.getStyleClass().remove(styleClass);
        }
    }

    private void toggle(String styleClass, boolean on) {
        if (on) {
            if (!card.getStyleClass().contains(styleClass)) card.getStyleClass().add(styleClass);
        } else {
            card.getStyleClass().remove(styleClass);
        }
    }

    /** Re-applies the node's title and summary in the current language. */
    void relocalize() {
        title.setText(I18n.t("kind." + node.kind.name()));
        refreshSummary();
    }

    void refreshSummary() {
        summary.setText(switch (node.kind) {
            case SOURCE -> sourceSummary();
            case EXTRACT -> I18n.t("pipeline.summary.pages",
                node.pages.isBlank() ? I18n.t("pipeline.summary.all") : node.pages)
                + (node.splitMode == com.pdfconduit.core.model.SplitMode.SEPARATE
                    ? "  ·  " + I18n.t("pipeline.summary.separate") : "");
            case ROTATE -> I18n.t("pipeline.summary.pages",
                node.pages.isBlank() ? I18n.t("pipeline.summary.all") : node.pages)
                + "  ·  " + node.angle + "°";
            case ARRANGE -> I18n.t("pipeline.summary.order",
                node.order.isBlank() ? I18n.t("pipeline.summary.natural") : node.order);
            case COMPRESS -> I18n.t("pipeline.summary.target", ProgressPanel.humanSize(node.targetBytes));
            case IMAGES_TO_PDF -> I18n.t("pipeline.node.pagesize") + " " + node.pageSize;
            case MERGE -> I18n.t("pipeline.summary.combine");
            case PROTECT, UNLOCK -> I18n.t(node.password == null || node.password.isBlank()
                ? "pipeline.summary.nopassword" : "pipeline.summary.haspassword");
            case METADATA -> I18n.t(node.metaStrip
                ? "pipeline.summary.metastrip" : "pipeline.summary.metaedit");
            case WATERMARK -> {
                boolean img = node.wmImage != null && !node.wmImage.isBlank();
                yield img ? I18n.t("pipeline.summary.watermarkimg")
                    : (node.wmText == null || node.wmText.isBlank() ? "—" : node.wmText);
            }
            case CROP -> I18n.t("pipeline.summary.crop",
                fmt(node.cropTop) + "/" + fmt(node.cropRight) + "/"
                    + fmt(node.cropBottom) + "/" + fmt(node.cropLeft),
                node.cropMm ? "mm" : "pt");
            case NUP -> node.nupBooklet
                ? I18n.t("nup.booklet")
                : I18n.t("nup.layout." + node.nupLayout.id());
            case TO_IMAGES -> node.imageFormat + "  ·  " + node.imageDpi + " DPI";
            case TO_TEXT -> "." + node.textFormat.extension();
        });
    }

    /** Compact number: drops a trailing {@code .0} so "10.0" shows as "10". */
    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    /** SOURCE summary: lists the first few file names, then "+N more". */
    private String sourceSummary() {
        if (node.files.isEmpty()) return I18n.t("pipeline.summary.nofiles");
        int max = 5;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(max, node.files.size()); i++) {
            if (i > 0) sb.append('\n');
            sb.append(node.files.get(i).getFileName());
        }
        if (node.files.size() > max) {
            sb.append('\n').append(I18n.t("pipeline.more", node.files.size() - max).trim());
        }
        return sb.toString();
    }
}
