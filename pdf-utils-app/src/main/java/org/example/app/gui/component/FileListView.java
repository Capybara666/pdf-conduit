package org.example.app.gui.component;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import org.example.core.util.FileTypeDetector;

import java.nio.file.Path;
import java.util.List;

public class FileListView extends ListView<Path> {

    public FileListView() {
        setItems(FXCollections.observableArrayList());
        setPrefHeight(160);
        setCellFactory(lv -> new FileCell(getItems()));
    }

    public void addFiles(List<Path> files) {
        for (Path p : files) {
            if (!getItems().contains(p)) getItems().add(p);
        }
    }

    public ObservableList<Path> getFiles() { return getItems(); }

    private static class FileCell extends ListCell<Path> {
        private final ObservableList<Path> items;

        FileCell(ObservableList<Path> items) { this.items = items; }

        @Override
        protected void updateItem(Path path, boolean empty) {
            super.updateItem(path, empty);
            if (empty || path == null) { setGraphic(null); return; }

            String icon = FileTypeDetector.isPdf(path) ? "📄" : "🖼";
            Label iconLabel = new Label(icon);
            Label nameLabel = new Label(path.getFileName().toString());
            nameLabel.setStyle("-fx-font-size: 11px;");

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Button deleteBtn = new Button("✕");
            deleteBtn.setStyle(
                "-fx-background-color: transparent; -fx-text-fill: #ef4444; -fx-cursor: hand;");
            deleteBtn.setOnAction(e -> items.remove(path));

            HBox row = new HBox(6, iconLabel, nameLabel, spacer, deleteBtn);
            row.getStyleClass().add("file-list-item");
            row.setStyle("-fx-alignment: CENTER_LEFT;");
            setGraphic(row);
            setText(null);

            ContextMenu menu = new ContextMenu();
            MenuItem moveUp   = new MenuItem("Move up");
            MenuItem moveDown = new MenuItem("Move down");
            MenuItem remove   = new MenuItem("Remove");
            moveUp.setOnAction(e -> {
                int idx = items.indexOf(path);
                if (idx > 0) { items.remove(idx); items.add(idx - 1, path); }
            });
            moveDown.setOnAction(e -> {
                int idx = items.indexOf(path);
                if (idx < items.size() - 1) { items.remove(idx); items.add(idx + 1, path); }
            });
            remove.setOnAction(e -> items.remove(path));
            menu.getItems().addAll(moveUp, moveDown, new SeparatorMenuItem(), remove);
            setContextMenu(menu);
        }
    }
}
