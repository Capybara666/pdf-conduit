package org.example.app.gui.pipeline;

import javafx.concurrent.Task;
import javafx.geometry.Point2D;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.example.app.pipeline.NodeKind;
import org.example.app.pipeline.PipelineExecutor;
import org.example.app.pipeline.PipelineModel;
import org.example.app.pipeline.PipelineNode;
import org.example.app.pipeline.PipelineValidator;
import org.example.app.pipeline.ValidationError;
import org.example.app.gui.util.FileOpener;
import org.example.app.gui.util.Sfx;
import org.example.app.i18n.I18n;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/** The Pipelines view: a draggable palette on top, the node canvas filling the
 *  width, and a slim bottom bar with the selected node's options + Run. */
public class PipelineView extends BorderPane {

    private static final List<NodeKind> PALETTE = List.of(
        NodeKind.SOURCE, NodeKind.MERGE, NodeKind.IMAGES_TO_PDF,
        NodeKind.EXTRACT, NodeKind.COMPRESS, NodeKind.ROTATE);

    private final PipelineModel model = new PipelineModel();
    private final PipelineCanvas canvas = new PipelineCanvas(model);
    private final NodeInspector inspector = new NodeInspector(canvas);
    private final Label status = new Label();
    private final Label banner = new Label();
    private final HBox resultLinks = new HBox(12);
    private final Button runBtn = new Button(I18n.t("pipeline.run"));

    public PipelineView() {
        getStyleClass().add("panel-root");
        canvas.setOnSelect(inspector::show);

        setTop(buildTop());
        ScrollPane scroll = new ScrollPane(canvas);
        scroll.setPannable(false);   // panning would steal the connection gesture
        scroll.getStyleClass().add("pipeline-scroll");
        setCenter(scroll);
        BorderPane.setMargin(scroll, new javafx.geometry.Insets(12, 0, 0, 0));

        HBox bottom = buildBottom();
        setBottom(bottom);
        BorderPane.setMargin(bottom, new javafx.geometry.Insets(12, 0, 0, 0));

        wirePaletteDrop();
    }

    // --- top: title + draggable palette -----------------------------------

    private VBox buildTop() {
        Label title = new Label(I18n.t("pipeline.title"));
        title.getStyleClass().add("panel-title");

        HBox palette = new HBox();
        palette.getStyleClass().add("pipeline-palette");
        for (NodeKind kind : PALETTE) palette.getChildren().add(chip(kind));

        Button clear = new Button(I18n.t("pipeline.clear"));
        clear.getStyleClass().add("btn-secondary");
        clear.setMinWidth(Region.USE_PREF_SIZE);
        clear.setOnAction(e -> { canvas.clearAll(); banner.setVisible(false); status.setText(""); });

        // Keep Clear next to the palette (left) so it doesn't track the canvas
        // scrollbar at the right edge as the window resizes.
        Separator sep = new Separator(javafx.geometry.Orientation.VERTICAL);
        HBox paletteRow = new HBox(8, palette, sep, clear);
        paletteRow.getStyleClass().add("pipeline-toolbar");

        banner.getStyleClass().add("error-banner");
        banner.setMaxWidth(Double.MAX_VALUE);
        banner.setWrapText(true);
        banner.setVisible(false);
        banner.managedProperty().bind(banner.visibleProperty());

        VBox top = new VBox(8, title, paletteRow, banner);
        return top;
    }

    private Label chip(NodeKind kind) {
        Label chip = new Label(I18n.t("kind." + kind.name()));
        chip.setGraphic(org.example.app.gui.icon.Icons.of(kind, 16));
        chip.setGraphicTextGap(8);
        chip.getStyleClass().add("pipeline-chip");
        chip.setOnDragDetected(e -> {
            Dragboard db = chip.startDragAndDrop(TransferMode.COPY);
            ClipboardContent content = new ClipboardContent();
            content.putString(kind.name());
            db.setContent(content);
            e.consume();
        });
        return chip;
    }

    /** Accept palette chips dropped onto the canvas; create the node at the drop point. */
    private void wirePaletteDrop() {
        canvas.setOnDragOver(e -> {
            if (e.getGestureSource() != canvas && e.getDragboard().hasString()) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        canvas.setOnDragDropped(e -> {
            Dragboard db = e.getDragboard();
            boolean done = false;
            if (db.hasString()) {
                NodeKind kind = NodeKind.valueOf(db.getString());
                Point2D p = canvas.sceneToLocal(e.getSceneX(), e.getSceneY());
                PipelineNode n = canvas.addNode(kind, Math.max(0, p.getX() - 20), Math.max(0, p.getY() - 16));
                if (kind == NodeKind.SOURCE) addFilesTo(n);
                inspector.show(n);
                done = true;
            }
            e.setDropCompleted(done);
            e.consume();
        });
    }

    private void addFilesTo(PipelineNode source) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("chooser.addfiles"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
            I18n.t("filter.supported"),
            org.example.core.convert.DocumentConverter.ALL_GLOBS.toArray(String[]::new)));
        var files = chooser.showOpenMultipleDialog(window());
        if (files != null) {
            for (File f : files) source.files.add(f.toPath());
            canvas.refreshNode(source);
        }
    }

    // --- bottom: node options + status + run ------------------------------

    private HBox buildBottom() {
        runBtn.getStyleClass().add("btn-primary");
        runBtn.setMinWidth(Region.USE_PREF_SIZE);   // never truncate the Run label
        runBtn.setOnAction(e -> run());
        resultLinks.setVisible(false);
        resultLinks.managedProperty().bind(resultLinks.visibleProperty());
        status.getStyleClass().add("pipeline-status");
        status.setMinWidth(Region.USE_PREF_SIZE);   // don't ellipsize the "saved N files" text
        HBox.setHgrow(inspector, Priority.ALWAYS);
        HBox bar = new HBox(12, inspector, status, resultLinks, runBtn);
        bar.getStyleClass().add("pipeline-bottom-bar");
        bar.setFillHeight(false);                    // keep buttons their natural height, centered
        return bar;
    }

    private void run() {
        List<ValidationError> errors = PipelineValidator.validate(model);
        if (!errors.isEmpty()) {
            canvas.highlightErrors(errors.stream()
                .map(ValidationError::nodeId).filter(id -> id != null).collect(Collectors.toSet()));
            banner.setText("⚠  " + errors.get(0).message()
                + (errors.size() > 1 ? I18n.t("pipeline.more", errors.size() - 1) : ""));
            banner.setVisible(true);
            return;
        }
        canvas.highlightErrors(java.util.Set.of());
        banner.setVisible(false);

        Task<PipelineExecutor.Result> task = new Task<>() {
            @Override
            protected PipelineExecutor.Result call() throws Exception {
                return PipelineExecutor.run(model, (done, total, msg) ->
                    updateMessage(msg + "  (" + done + "/" + total + ")"));
            }
        };
        runBtn.setDisable(true);
        resultLinks.setVisible(false);
        status.textProperty().bind(task.messageProperty());
        task.setOnSucceeded(e -> {
            status.textProperty().unbind();
            runBtn.setDisable(false);
            List<Path> saved = task.getValue().savedByNode().values().stream()
                .flatMap(List::stream).toList();
            status.setText(I18n.t("pipeline.done", saved.size()));
            showResultLinks(saved);
            Sfx.playDone();
        });
        task.setOnFailed(e -> {
            status.textProperty().unbind();
            runBtn.setDisable(false);
            status.setText("");
            resultLinks.setVisible(false);
            Throwable ex = task.getException();
            banner.setText("⚠  " + (ex == null ? I18n.t("pipeline.fail") : ex.getMessage()));
            banner.setVisible(true);
            Sfx.playError();
        });
        Thread t = new Thread(task, "pipeline-run");
        t.setDaemon(true);
        t.start();
    }

    /** After a successful run, offer links to open the produced files / folders. */
    private void showResultLinks(List<Path> saved) {
        resultLinks.getChildren().clear();
        if (saved.isEmpty()) { resultLinks.setVisible(false); return; }
        if (saved.size() == 1) {
            Path file = saved.get(0);
            resultLinks.getChildren().addAll(
                resultLink(I18n.t("link.openfile"), () -> FileOpener.open(file)),
                resultLink(I18n.t("link.openfolder"), () -> FileOpener.open(file.getParent())));
        } else {
            List<Path> folders = saved.stream().map(Path::getParent)
                .filter(java.util.Objects::nonNull).distinct().toList();
            resultLinks.getChildren().add(
                resultLink(I18n.t("link.openfolder"), () -> folders.forEach(FileOpener::open)));
        }
        resultLinks.setVisible(true);
    }

    private Button resultLink(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add("result-link");
        b.setMinWidth(Region.USE_PREF_SIZE);
        b.setOnAction(e -> action.run());
        return b;
    }

    private Stage window() {
        return (Stage) getScene().getWindow();
    }
}
