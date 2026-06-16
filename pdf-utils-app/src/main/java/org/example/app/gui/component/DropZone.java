package org.example.app.gui.component;

import javafx.scene.control.Label;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.example.app.gui.Animations;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

public class DropZone extends VBox {

    public DropZone(Consumer<List<Path>> onFiles) {
        getStyleClass().add("drop-zone");

        Label icon = new Label("📄");
        icon.setStyle("-fx-font-size: 20px;");
        Label primary = new Label("Drag & drop PDF or image files here");
        Label secondary = new Label("or click to select");
        secondary.setStyle("-fx-font-size: 11px; -fx-opacity: 0.6;");
        getChildren().addAll(icon, primary, secondary);

        setOnDragOver(e -> {
            if (e.getGestureSource() != this && e.getDragboard().hasFiles()) {
                e.acceptTransferModes(TransferMode.COPY);
                if (!getStyleClass().contains("drag-over")) {
                    getStyleClass().add("drag-over");
                    Animations.scaleTo(this, 1.02);
                }
            }
            e.consume();
        });
        setOnDragExited(e -> {
            getStyleClass().remove("drag-over");
            Animations.scaleTo(this, 1.0);
        });
        setOnDragDropped(e -> {
            Dragboard db = e.getDragboard();
            if (db.hasFiles()) {
                onFiles.accept(db.getFiles().stream().map(File::toPath).toList());
                e.setDropCompleted(true);
            }
            getStyleClass().remove("drag-over");
            Animations.scaleTo(this, 1.0);
            e.consume();
        });
        setOnMouseClicked(e -> openChooser(onFiles));
    }

    private void openChooser(Consumer<List<Path>> onFiles) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select files");
        chooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("PDF & Images",
                "*.pdf", "*.png", "*.jpg", "*.jpeg", "*.webp", "*.tiff", "*.tif", "*.bmp"),
            new FileChooser.ExtensionFilter("PDF", "*.pdf"),
            new FileChooser.ExtensionFilter("Images",
                "*.png", "*.jpg", "*.jpeg", "*.webp", "*.tiff", "*.bmp")
        );
        Stage stage = (Stage) getScene().getWindow();
        List<File> files = chooser.showOpenMultipleDialog(stage);
        if (files != null) {
            onFiles.accept(files.stream().map(File::toPath).toList());
        }
    }
}
