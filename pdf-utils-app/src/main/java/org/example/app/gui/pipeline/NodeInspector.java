package org.example.app.gui.pipeline;

import javafx.collections.FXCollections;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.example.app.pipeline.PipelineGraph;
import org.example.app.pipeline.PipelineNode;
import org.example.core.model.PageSize;

import java.io.File;
import java.nio.file.Path;

/** Right-hand panel showing the editable options of the selected node. */
class NodeInspector extends VBox {

    private final PipelineCanvas canvas;

    NodeInspector(PipelineCanvas canvas) {
        this.canvas = canvas;
        getStyleClass().add("pipeline-inspector");
        setSpacing(10);
        setPrefWidth(280);
        setMinWidth(280);
        show(null);
    }

    void show(PipelineNode node) {
        getChildren().clear();
        if (node == null) {
            Label hint = new Label("Select a node to edit its options.");
            hint.getStyleClass().add("pipeline-inspector-hint");
            hint.setWrapText(true);
            getChildren().add(hint);
            return;
        }

        Label title = new Label(node.kind.label);
        title.getStyleClass().add("panel-title");
        getChildren().add(title);

        switch (node.kind) {
            case SOURCE -> buildSource(node);
            case EXTRACT -> buildPages(node);
            case ROTATE -> buildRotate(node);
            case COMPRESS -> buildCompress(node);
            case IMAGES_TO_PDF -> buildImages(node);
            case MERGE -> getChildren().add(info("Combines all connected inputs into a single PDF."));
        }

        if (canvas.model.isTerminal(node)) {
            buildDestination(node);
        }
    }

    // --- per-kind option blocks -------------------------------------------

    private void buildSource(PipelineNode node) {
        ListView<Path> list = new ListView<>(FXCollections.observableArrayList(node.files));
        list.setPrefHeight(200);
        Button add = secondary("Add files…");
        add.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Add source files");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "PDF & images", "*.pdf", "*.png", "*.jpg", "*.jpeg", "*.webp", "*.tiff", "*.tif", "*.bmp"));
            var files = chooser.showOpenMultipleDialog(window());
            if (files != null) {
                for (File f : files) {
                    if (!node.files.contains(f.toPath())) node.files.add(f.toPath());
                }
                list.setItems(FXCollections.observableArrayList(node.files));
                canvas.refreshNode(node);
            }
        });
        Button remove = secondary("Remove");
        remove.setOnAction(e -> {
            Path sel = list.getSelectionModel().getSelectedItem();
            if (sel != null) {
                node.files.remove(sel);
                list.setItems(FXCollections.observableArrayList(node.files));
                canvas.refreshNode(node);
            }
        });
        getChildren().addAll(new Label("Files:"), list, row(add, remove));
    }

    private void buildPages(PipelineNode node) {
        TextField pages = new TextField(node.pages);
        pages.setPromptText("1-3,5  (blank = all)");
        pages.textProperty().addListener((o, a, b) -> { node.pages = b; canvas.refreshNode(node); });
        getChildren().addAll(new Label("Pages:"), pages);
    }

    private void buildRotate(PipelineNode node) {
        TextField pages = new TextField(node.pages);
        pages.setPromptText("1-3,5  (blank = all)");
        pages.textProperty().addListener((o, a, b) -> { node.pages = b; canvas.refreshNode(node); });

        ComboBox<Integer> angle = new ComboBox<>(FXCollections.observableArrayList(90, 180, 270));
        angle.setValue(node.angle);
        angle.valueProperty().addListener((o, a, b) -> { if (b != null) { node.angle = b; canvas.refreshNode(node); } });

        getChildren().addAll(new Label("Pages:"), pages, new Label("Angle:"), angle);
    }

    private void buildCompress(PipelineNode node) {
        long bytes = node.targetBytes;
        boolean mb = bytes % (1024 * 1024) == 0;
        TextField size = new TextField(String.valueOf(mb ? bytes / (1024 * 1024) : bytes / 1024));
        ComboBox<String> unit = new ComboBox<>(FXCollections.observableArrayList("MB", "KB"));
        unit.setValue(mb ? "MB" : "KB");
        Runnable apply = () -> {
            try {
                double v = Double.parseDouble(size.getText().strip());
                node.targetBytes = unit.getValue().equals("MB")
                    ? (long) (v * 1024 * 1024) : (long) (v * 1024);
                canvas.refreshNode(node);
            } catch (NumberFormatException ignored) {}
        };
        size.textProperty().addListener((o, a, b) -> apply.run());
        unit.valueProperty().addListener((o, a, b) -> apply.run());
        getChildren().addAll(new Label("Target size:"), row(size, unit));
    }

    private void buildImages(PipelineNode node) {
        ComboBox<PageSize> box = new ComboBox<>(FXCollections.observableArrayList(PageSize.values()));
        box.setValue(node.pageSize);
        box.valueProperty().addListener((o, a, b) -> { if (b != null) { node.pageSize = b; canvas.refreshNode(node); } });
        getChildren().addAll(new Label("Page size:"), box);
    }

    private void buildDestination(PipelineNode node) {
        boolean multiple = PipelineGraph.outputCount(canvas.model, node.id) > 1;
        Label label = new Label(multiple ? "Output folder:" : "Output file:");
        TextField field = new TextField(node.outputDestination);
        field.setPromptText(multiple ? "Folder for results…" : "Output file path…");
        field.textProperty().addListener((o, a, b) -> node.outputDestination = b);
        Button browse = secondary("…");
        browse.setOnAction(e -> {
            if (multiple) {
                DirectoryChooser dc = new DirectoryChooser();
                dc.setTitle("Select output folder");
                File dir = dc.showDialog(window());
                if (dir != null) field.setText(dir.getAbsolutePath());
            } else {
                FileChooser fc = new FileChooser();
                fc.setTitle("Save as");
                fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF", "*.pdf"));
                File f = fc.showSaveDialog(window());
                if (f != null) field.setText(f.getAbsolutePath());
            }
        });
        HBox.setHgrow(field, Priority.ALWAYS);
        VBox box = new VBox(4, new Region(), label, new HBox(6, field, browse));
        box.getStyleClass().add("pipeline-inspector-output");
        getChildren().add(box);
    }

    // --- helpers ----------------------------------------------------------

    private Label info(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.getStyleClass().add("pipeline-inspector-hint");
        return l;
    }

    private Button secondary(String text) {
        Button b = new Button(text);
        b.getStyleClass().add("btn-secondary");
        return b;
    }

    private HBox row(javafx.scene.Node... nodes) {
        HBox h = new HBox(6, nodes);
        return h;
    }

    private Stage window() {
        return (Stage) getScene().getWindow();
    }
}
