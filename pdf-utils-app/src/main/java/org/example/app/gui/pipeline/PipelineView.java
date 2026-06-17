package org.example.app.gui.pipeline;

import javafx.concurrent.Task;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
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

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;

/** The Pipelines view: toolbar + node canvas + inspector, plus run/validation. */
public class PipelineView extends BorderPane {

    private final PipelineModel model = new PipelineModel();
    private final PipelineCanvas canvas = new PipelineCanvas(model);
    private final NodeInspector inspector = new NodeInspector(canvas);
    private final Label status = new Label();
    private final Label banner = new Label();
    private final Button runBtn = new Button("▶  Run pipeline");
    private int placed = 0;

    public PipelineView() {
        getStyleClass().add("panel-root");
        canvas.setOnSelect(inspector::show);

        setTop(buildHeader());
        ScrollPane scroll = new ScrollPane(canvas);
        // Panning is intentionally off: a pannable ScrollPane steals the mouse
        // gesture and breaks port-to-port dragging. Navigate with the scrollbars.
        scroll.setPannable(false);
        scroll.getStyleClass().add("pipeline-scroll");
        setCenter(scroll);
        setRight(inspector);
    }

    private VBox buildHeader() {
        Label title = new Label("Pipeline");
        title.getStyleClass().add("panel-title");

        Button addFiles = new Button("Add files…");
        addFiles.getStyleClass().add("btn-secondary");
        addFiles.setOnAction(e -> addSource());

        MenuButton addOp = new MenuButton("Add operation");
        addOp.getStyleClass().add("btn-secondary");
        for (NodeKind kind : List.of(NodeKind.MERGE, NodeKind.IMAGES_TO_PDF,
                                     NodeKind.EXTRACT, NodeKind.COMPRESS, NodeKind.ROTATE)) {
            MenuItem item = new MenuItem(kind.label);
            item.setOnAction(e -> place(kind));
            addOp.getItems().add(item);
        }

        Button clear = new Button("Clear");
        clear.getStyleClass().add("btn-secondary");
        clear.setOnAction(e -> { canvas.clearAll(); banner.setVisible(false); status.setText(""); });

        runBtn.getStyleClass().add("btn-primary");
        runBtn.setOnAction(e -> run());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(8, addFiles, addOp, clear, spacer, status, runBtn);
        toolbar.getStyleClass().add("pipeline-toolbar");

        banner.getStyleClass().add("error-banner");
        banner.setMaxWidth(Double.MAX_VALUE);
        banner.setWrapText(true);
        banner.setVisible(false);
        banner.managedProperty().bind(banner.visibleProperty());

        VBox header = new VBox(10, title, toolbar, banner);
        return header;
    }

    private void addSource() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Add source files");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
            "PDF & images", "*.pdf", "*.png", "*.jpg", "*.jpeg", "*.webp", "*.tiff", "*.tif", "*.bmp"));
        var files = chooser.showOpenMultipleDialog(window());
        if (files == null || files.isEmpty()) return;
        PipelineNode n = place(NodeKind.SOURCE);
        for (File f : files) n.files.add(f.toPath());
        canvas.refreshNode(n);
        inspector.show(n);
    }

    private PipelineNode place(NodeKind kind) {
        double x = 60 + (placed % 4) * 220;
        double y = 60 + (placed % 3) * 150;
        placed++;
        return canvas.addNode(kind, x, y);
    }

    private void run() {
        List<ValidationError> errors = PipelineValidator.validate(model);
        if (!errors.isEmpty()) {
            canvas.highlightErrors(errors.stream()
                .map(ValidationError::nodeId).filter(id -> id != null).collect(Collectors.toSet()));
            banner.setText("⚠  " + errors.get(0).message()
                + (errors.size() > 1 ? "  (+" + (errors.size() - 1) + " more)" : ""));
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
        status.textProperty().bind(task.messageProperty());
        task.setOnSucceeded(e -> {
            status.textProperty().unbind();
            runBtn.setDisable(false);
            int n = task.getValue().savedByNode().values().stream().mapToInt(List::size).sum();
            status.setText("Done — saved " + n + (n == 1 ? " file." : " files."));
        });
        task.setOnFailed(e -> {
            status.textProperty().unbind();
            runBtn.setDisable(false);
            status.setText("");
            Throwable ex = task.getException();
            banner.setText("⚠  " + (ex == null ? "Pipeline failed." : ex.getMessage()));
            banner.setVisible(true);
        });
        Thread t = new Thread(task, "pipeline-run");
        t.setDaemon(true);
        t.start();
    }

    private Stage window() {
        return (Stage) getScene().getWindow();
    }
}
