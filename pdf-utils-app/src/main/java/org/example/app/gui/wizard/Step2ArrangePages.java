package org.example.app.gui.wizard;

import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.VBox;
import org.example.core.model.PageSource;

public class Step2ArrangePages implements WizardStep {

    private final WizardModel model;

    public Step2ArrangePages(WizardModel model) { this.model = model; }

    @Override
    public Node getContent() {
        Label title = new Label("Step 2: Arrange pages");
        title.getStyleClass().add("panel-title");
        Label hint = new Label("Drag rows to reorder. Right-click to remove.");
        hint.setStyle("-fx-font-size: 11px; -fx-opacity: 0.6;");

        ListView<PageSource> listView = new ListView<>(model.pages);
        listView.setPrefHeight(300);
        listView.setCellFactory(lv -> new DraggablePageCell(model));

        VBox box = new VBox(10, title, hint, listView);
        box.setStyle("-fx-padding: 18;");
        return box;
    }

    private static class DraggablePageCell extends ListCell<PageSource> {
        private final WizardModel model;

        DraggablePageCell(WizardModel model) { this.model = model; }

        @Override
        protected void updateItem(PageSource src, boolean empty) {
            super.updateItem(src, empty);
            if (empty || src == null) { setText(null); setGraphic(null); return; }

            String name = switch (src) {
                case PageSource.PdfPageSource ps -> "📄 " + ps.file().getFileName()
                    + (ps.range().isAll() ? " (all pages)" : " pages " + ps.range().pageNumbers());
                case PageSource.ImageSource is -> "🖼 " + is.file().getFileName();
            };
            setText(name);

            setOnDragDetected(e -> {
                Dragboard db = startDragAndDrop(TransferMode.MOVE);
                ClipboardContent cc = new ClipboardContent();
                cc.putString(String.valueOf(getIndex()));
                db.setContent(cc);
                e.consume();
            });
            setOnDragOver(e -> {
                if (e.getGestureSource() != this && e.getDragboard().hasString()) {
                    e.acceptTransferModes(TransferMode.MOVE);
                }
                e.consume();
            });
            setOnDragDropped(e -> {
                int from = Integer.parseInt(e.getDragboard().getString());
                int to = getIndex();
                if (from != to && from >= 0 && to >= 0
                        && from < model.pages.size() && to < model.pages.size()) {
                    PageSource moved = model.pages.remove(from);
                    model.pages.add(to, moved);
                }
                e.setDropCompleted(true);
                e.consume();
            });
            setOnContextMenuRequested(e -> {
                ContextMenu menu = new ContextMenu();
                MenuItem remove = new MenuItem("Remove");
                remove.setOnAction(ev -> model.pages.remove(src));
                menu.getItems().add(remove);
                menu.show(this, e.getScreenX(), e.getScreenY());
            });
        }
    }
}
