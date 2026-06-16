package org.example.app.gui.component;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.*;
import javafx.scene.input.*;
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
        private final Label dragHandle = new Label("⠿");
        private final Label iconLabel  = new Label();
        private final Label nameLabel  = new Label();
        private final Button deleteBtn = new Button("✕");
        private final MenuItem moveUpItem   = new MenuItem("Move up");
        private final MenuItem moveDownItem = new MenuItem("Move down");
        private final MenuItem removeItem   = new MenuItem("Remove");
        private final HBox row;

        FileCell(ObservableList<Path> items) {
            this.items = items;

            dragHandle.setStyle("-fx-font-size: 10px; -fx-opacity: 0.35;");
            nameLabel.setStyle("-fx-font-size: 11px;");
            deleteBtn.setStyle(
                "-fx-background-color: transparent; -fx-text-fill: #ef4444;" +
                " -fx-cursor: hand; -fx-padding: 0 4 0 4; -fx-font-size: 11px;");

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            row = new HBox(6, dragHandle, iconLabel, nameLabel, spacer, deleteBtn);
            row.getStyleClass().add("file-list-item");
            row.setMaxWidth(Double.MAX_VALUE);

            setContextMenu(new ContextMenu(
                moveUpItem, moveDownItem, new SeparatorMenuItem(), removeItem));
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);

            setOnDragDetected(e -> {
                if (getItem() == null) return;
                Dragboard db = startDragAndDrop(TransferMode.MOVE);
                ClipboardContent cc = new ClipboardContent();
                cc.putString(String.valueOf(getIndex()));
                db.setContent(cc);
                e.consume();
            });
            setOnDragOver(e -> {
                if (e.getGestureSource() != this && e.getDragboard().hasString())
                    e.acceptTransferModes(TransferMode.MOVE);
                e.consume();
            });
            setOnDragDropped(e -> {
                Dragboard db = e.getDragboard();
                if (!db.hasString()) { e.setDropCompleted(false); e.consume(); return; }
                int from = Integer.parseInt(db.getString());
                int to   = getIndex();
                if (from != to && from >= 0 && to >= 0
                        && from < items.size() && to < items.size()) {
                    Path moved = items.remove(from);
                    items.add(to, moved);
                }
                e.setDropCompleted(true);
                e.consume();
            });
        }

        @Override
        protected void updateItem(Path path, boolean empty) {
            super.updateItem(path, empty);
            setText(null);
            if (empty || path == null) { setGraphic(null); return; }

            iconLabel.setText(FileTypeDetector.isPdf(path) ? "📄" : "🖼");
            nameLabel.setText(path.getFileName().toString());

            deleteBtn.setOnAction(e -> items.remove(path));
            moveUpItem.setOnAction(e -> {
                int idx = items.indexOf(path);
                if (idx > 0) { items.remove(idx); items.add(idx - 1, path); }
            });
            moveDownItem.setOnAction(e -> {
                int idx = items.indexOf(path);
                if (idx < items.size() - 1) { items.remove(idx); items.add(idx + 1, path); }
            });
            removeItem.setOnAction(e -> items.remove(path));

            setGraphic(row);
        }
    }
}
