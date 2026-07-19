package com.pdfconduit.app.gui.pipeline;

import javafx.concurrent.Task;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import com.pdfconduit.core.pipeline.NodeKind;
import com.pdfconduit.core.pipeline.PipelineExecutor;
import com.pdfconduit.core.pipeline.PipelineModel;
import com.pdfconduit.core.pipeline.PipelineNode;
import com.pdfconduit.core.pipeline.PipelineStore;
import com.pdfconduit.core.pipeline.PipelineValidator;
import com.pdfconduit.core.pipeline.ValidationError;
import com.pdfconduit.app.gui.util.FileOpener;
import com.pdfconduit.app.gui.util.Sfx;
import com.pdfconduit.app.i18n.I18n;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/** The Pipelines view: a draggable palette on top, the node canvas filling the
 *  width, and a slim bottom bar with the selected node's options + Run. */
public class PipelineView extends BorderPane {

    private static final List<NodeKind> PALETTE = List.of(
        NodeKind.SOURCE, NodeKind.MERGE, NodeKind.IMAGES_TO_PDF,
        NodeKind.EXTRACT, NodeKind.COMPRESS, NodeKind.ROTATE, NodeKind.ARRANGE,
        NodeKind.PROTECT, NodeKind.UNLOCK, NodeKind.METADATA, NodeKind.WATERMARK,
        NodeKind.CROP, NodeKind.NUP, NodeKind.PAGE_MARKS, NodeKind.TO_IMAGES, NodeKind.TO_TEXT);

    private final PipelineModel model = new PipelineModel();
    private final PipelineCanvas canvas = new PipelineCanvas(model);
    private final NodeInspector inspector = new NodeInspector(canvas);
    private final Label status = new Label();
    private final Label banner = new Label();
    private final HBox resultLinks = new HBox(12);
    private final Button runBtn = new Button();

    public PipelineView() {
        getStyleClass().add("panel-root");
        canvas.setOnSelect(inspector::show);

        setTop(buildTop());
        ScrollPane scroll = new ScrollPane(canvas);
        scroll.setPannable(false);   // panning would steal the connection gesture
        scroll.getStyleClass().add("pipeline-scroll");

        // Empty-state hint, centered over the canvas; vanishes once a node exists
        // so it never clutters an in-progress pipeline.
        Label emptyHint = new Label();
        I18n.bindText(emptyHint::setText, "pipeline.empty.hint");
        emptyHint.getStyleClass().add("pipeline-empty-hint");
        emptyHint.setWrapText(true);
        emptyHint.setMaxWidth(440);
        emptyHint.setTextAlignment(TextAlignment.CENTER);
        emptyHint.setMouseTransparent(true);   // let drops reach the canvas
        StackPane center = new StackPane(scroll, emptyHint);
        StackPane.setAlignment(emptyHint, Pos.CENTER);
        setCenter(center);
        BorderPane.setMargin(center, new javafx.geometry.Insets(12, 0, 0, 0));
        canvas.setOnChange(() -> emptyHint.setVisible(canvas.isEmpty()));
        emptyHint.setVisible(canvas.isEmpty());

        HBox bottom = buildBottom();
        setBottom(bottom);
        BorderPane.setMargin(bottom, new javafx.geometry.Insets(12, 0, 0, 0));

        wirePaletteDrop();
    }

    // --- top: title + draggable palette -----------------------------------

    private VBox buildTop() {
        Label title = new Label();
        I18n.bindText(title::setText, "pipeline.title");
        title.getStyleClass().add("panel-title");

        FlowPane palette = new FlowPane(6, 6);
        palette.getStyleClass().add("pipeline-palette");
        for (NodeKind kind : PALETTE) palette.getChildren().add(chip(kind));

        Button save = new Button();
        I18n.bindText(save::setText, "pipeline.save");
        save.getStyleClass().add("btn-secondary");
        save.setMinWidth(Region.USE_PREF_SIZE);
        save.setOnAction(e -> savePipeline());

        Button load = new Button();
        I18n.bindText(load::setText, "pipeline.load");
        load.getStyleClass().add("btn-secondary");
        load.setMinWidth(Region.USE_PREF_SIZE);
        load.setOnAction(e -> loadPipeline());

        Button clear = new Button();
        I18n.bindText(clear::setText, "pipeline.clear");
        clear.getStyleClass().add("btn-secondary");
        clear.setMinWidth(Region.USE_PREF_SIZE);
        clear.setOnAction(e -> { canvas.clearAll(); banner.setVisible(false); status.setText(""); });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button help = new Button("?");
        help.getStyleClass().add("btn-secondary");
        help.setMinWidth(Region.USE_PREF_SIZE);
        var helpTip = new javafx.scene.control.Tooltip();
        I18n.bindText(helpTip::setText, "pipeline.help.tooltip");
        help.setTooltip(helpTip);
        help.setOnAction(e -> showHelp());

        // The palette wraps to as many rows as needed (so it scales as blocks are
        // added); Clear/Help sit on their own row below it.
        HBox controls = new HBox(8, save, load, clear, spacer, help);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.getStyleClass().add("pipeline-toolbar");

        banner.getStyleClass().add("error-banner");
        banner.setMaxWidth(Double.MAX_VALUE);
        banner.setWrapText(true);
        banner.setVisible(false);
        banner.managedProperty().bind(banner.visibleProperty());

        VBox top = new VBox(8, title, palette, controls, banner);
        return top;
    }

    private Label chip(NodeKind kind) {
        Label chip = new Label();
        I18n.bindText(chip::setText, "kind." + kind.name());
        chip.setGraphic(com.pdfconduit.app.gui.icon.Icons.of(kind, 16));
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
            com.pdfconduit.core.convert.DocumentConverter.ALL_GLOBS.toArray(String[]::new)));
        var files = chooser.showOpenMultipleDialog(window());
        if (files != null) {
            for (File f : files) source.files.add(f.toPath());
            canvas.refreshNode(source);
        }
    }

    // --- bottom: node options + status + run ------------------------------

    private HBox buildBottom() {
        I18n.bindText(runBtn::setText, "pipeline.run");
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
        bar.setFillHeight(false);                    // keep buttons their natural height
        bar.setAlignment(Pos.CENTER_LEFT);           // centre status/Run against a taller (wrapped) inspector
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

    private void showHelp() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(I18n.t("pipeline.help.title"));
        alert.setHeaderText(I18n.t("pipeline.help.title"));
        alert.setContentText(I18n.t("pipeline.help.body"));
        alert.initOwner(window());
        if (getScene() != null) {
            alert.getDialogPane().getStylesheets().addAll(getScene().getStylesheets());
        }
        alert.getDialogPane().setMinWidth(460);
        alert.showAndWait();
    }

    private void savePipeline() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("pipeline.save"));
        chooser.setInitialFileName("pipeline.json");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Pipeline (*.json)", "*.json"));
        File f = chooser.showSaveDialog(window());
        if (f == null) return;
        Path p = f.toPath();
        if (!p.toString().toLowerCase().endsWith(".json")) p = Path.of(p + ".json");
        try {
            PipelineStore.save(model, p);
            banner.setVisible(false);
            status.setText(I18n.t("pipeline.saved"));
        } catch (IOException ex) {
            showError(ex.getMessage());
        }
    }

    private void loadPipeline() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("pipeline.load"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Pipeline (*.json)", "*.json"));
        File f = chooser.showOpenDialog(window());
        if (f == null) return;
        try {
            canvas.loadModel(PipelineStore.load(f.toPath()));
            banner.setVisible(false);
            status.setText(I18n.t("pipeline.loaded"));
        } catch (IOException ex) {
            showError(ex.getMessage());
        }
    }

    private void showError(String message) {
        banner.setText("⚠  " + message);
        banner.setVisible(true);
    }

    private Stage window() {
        return (Stage) getScene().getWindow();
    }
}
