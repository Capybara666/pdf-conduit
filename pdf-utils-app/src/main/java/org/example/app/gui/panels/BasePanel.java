package org.example.app.gui.panels;

import javafx.collections.ListChangeListener;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.example.app.gui.component.DropZone;
import org.example.app.gui.component.FileListView;
import org.example.app.gui.component.ProgressPanel;

import java.nio.file.Path;

public abstract class BasePanel extends VBox {

    protected final FileListView fileList;
    protected final DropZone dropZone;
    protected final TextField outputField;
    protected final ProgressPanel progressPanel;

    protected BasePanel(String title, String runLabel, String outputSuffix) {
        getStyleClass().add("panel-root");

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("panel-title");

        fileList = new FileListView();
        VBox.setVgrow(fileList, Priority.ALWAYS);
        dropZone = new DropZone(fileList::addFiles);

        outputField = new TextField();
        outputField.setPromptText("Output file path…");
        outputField.setEditable(true);
        HBox.setHgrow(outputField, Priority.ALWAYS);

        fileList.getFiles().addListener((ListChangeListener<Path>) change -> {
            if (!outputField.getText().isBlank() || fileList.getFiles().isEmpty()) return;
            Path first = fileList.getFiles().get(0);
            String stem = stripExt(first.getFileName().toString());
            outputField.setText(
                first.getParent().resolve("pdf-utils")
                     .resolve(stem + outputSuffix + ".pdf").toString());
        });

        Button browseBtn = new Button("…");
        browseBtn.getStyleClass().add("btn-secondary");
        browseBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Save as");
            chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("PDF", "*.pdf"));
            Stage stage = (Stage) getScene().getWindow();
            var file = chooser.showSaveDialog(stage);
            if (file != null) outputField.setText(file.getAbsolutePath());
        });
        HBox outputRow = new HBox(6, outputField, browseBtn);

        progressPanel = new ProgressPanel(runLabel);
        progressPanel.getRunButton().setOnAction(e -> onRun());

        getChildren().addAll(titleLabel, dropZone, fileList, buildOptionsArea(), outputRow, progressPanel);
    }

    protected abstract VBox buildOptionsArea();

    protected abstract void onRun();

    protected Path resolveOutput(Path defaultPath) {
        String text = outputField.getText();
        return (text != null && !text.isBlank()) ? Path.of(text) : defaultPath;
    }

    protected static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }
}
