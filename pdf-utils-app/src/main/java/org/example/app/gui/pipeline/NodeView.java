package org.example.app.gui.pipeline;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import org.example.app.gui.component.ProgressPanel;
import org.example.app.pipeline.PipelineNode;

/** Visual card for a {@link PipelineNode}: header (drag/close), summary, ports. */
class NodeView extends HBox {

    final PipelineNode node;
    private final Circle inPort;
    private final Circle outPort;
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

        Label title = new Label(node.kind.label);
        title.getStyleClass().add("pipeline-node-title");
        Button close = new Button("✕");
        close.getStyleClass().add("pipeline-node-close");
        close.setOnAction(e -> canvas.removeNode(node));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(6, title, spacer, close);
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

        // Connection gesture: drag from the output port to an input port.
        outPort.setOnDragDetected(e -> {
            outPort.startFullDrag();
            canvas.beginConnect(node);
            e.consume();
        });
        outPort.setOnMouseDragged(e -> canvas.updateTempEnd(e.getSceneX(), e.getSceneY()));
        outPort.setOnMouseReleased(e -> canvas.cancelConnect());
        inPort.setOnMouseDragReleased(e -> {
            canvas.finishConnect(node);
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

    private void toggle(String styleClass, boolean on) {
        if (on) {
            if (!card.getStyleClass().contains(styleClass)) card.getStyleClass().add(styleClass);
        } else {
            card.getStyleClass().remove(styleClass);
        }
    }

    void refreshSummary() {
        summary.setText(switch (node.kind) {
            case SOURCE -> node.files.isEmpty() ? "no files"
                : node.files.size() + (node.files.size() == 1 ? " file" : " files");
            case EXTRACT -> "pages: " + (node.pages.isBlank() ? "all" : node.pages);
            case ROTATE -> (node.pages.isBlank() ? "all" : node.pages) + "  ·  " + node.angle + "°";
            case COMPRESS -> "target ≤ " + ProgressPanel.humanSize(node.targetBytes);
            case IMAGES_TO_PDF -> "page size: " + node.pageSize;
            case MERGE -> "combine inputs";
        });
    }
}
