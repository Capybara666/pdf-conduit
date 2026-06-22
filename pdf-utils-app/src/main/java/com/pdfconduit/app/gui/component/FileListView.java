package com.pdfconduit.app.gui.component;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import com.pdfconduit.app.i18n.I18n;
import com.pdfconduit.core.util.FileTypeDetector;

import java.nio.file.Path;
import java.util.List;

public class FileListView extends ListView<Path> {

    public FileListView() {
        setItems(FXCollections.observableArrayList());
        setPrefHeight(160);
        getStyleClass().add("file-list-view");
        setCellFactory(lv -> new FileCell(getItems()));
        setPlaceholder(emptyPlaceholder());
    }

    /** Friendly empty state shown when no files have been added yet (onboarding). */
    private static VBox emptyPlaceholder() {
        Label icon = new Label("📂");
        icon.getStyleClass().add("empty-icon");
        Label msg = new Label();
        I18n.bindText(msg::setText, "empty.files");
        msg.getStyleClass().add("text-caption");
        msg.setWrapText(true);
        VBox box = new VBox(8, icon, msg);
        box.getStyleClass().add("empty-state");
        box.setAlignment(javafx.geometry.Pos.CENTER);
        return box;
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

            dragHandle.getStyleClass().add("text-handle");
            nameLabel.getStyleClass().add("text-sm");
            deleteBtn.getStyleClass().add("file-delete-btn");

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            row = new HBox(6, dragHandle, iconLabel, nameLabel, spacer, deleteBtn);
            row.getStyleClass().add("file-list-item");
            row.setMaxWidth(Double.MAX_VALUE);

            setContextMenu(new ContextMenu(
                moveUpItem, moveDownItem, new SeparatorMenuItem(), removeItem));
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);

            DragReorder.install(this, items);
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
