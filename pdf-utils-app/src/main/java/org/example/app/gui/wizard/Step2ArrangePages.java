package org.example.app.gui.wizard;

import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.example.core.model.PageSource;

public class Step2ArrangePages implements WizardStep {

    private final WizardModel model;

    public Step2ArrangePages(WizardModel model) { this.model = model; }

    @Override
    public Node getContent() {
        Label title = new Label("Step 2: Arrange pages");
        title.getStyleClass().add("panel-title");
        Label hint = new Label("Drag rows to reorder, or use ↑/↓ buttons.");
        hint.setStyle("-fx-font-size: 11px; -fx-opacity: 0.6;");

        ListView<PageSource> listView = new ListView<>(model.pages);
        listView.setCellFactory(lv -> new DraggablePageCell(model));
        VBox.setVgrow(listView, Priority.ALWAYS);

        VBox box = new VBox(10, title, hint, listView);
        box.setStyle("-fx-padding: 18;");
        return box;
    }

    private static class DraggablePageCell extends ListCell<PageSource> {
        private final WizardModel model;
        private final Label dragHandle = new Label("⠿");
        private final Label nameLabel  = new Label();
        private final Button upBtn     = new Button("↑");
        private final Button downBtn   = new Button("↓");
        private final Button removeBtn = new Button("✕");
        private final HBox row;

        DraggablePageCell(WizardModel model) {
            this.model = model;

            dragHandle.setStyle("-fx-font-size: 10px; -fx-opacity: 0.35;");
            String btnStyle = "-fx-padding: 2 7 2 7; -fx-font-size: 11px;";
            upBtn.setStyle(btnStyle);
            downBtn.setStyle(btnStyle);
            removeBtn.setStyle(btnStyle +
                " -fx-text-fill: #ef4444; -fx-background-color: transparent;");

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            row = new HBox(6, dragHandle, nameLabel, spacer, upBtn, downBtn, removeBtn);
            row.setStyle("-fx-alignment: CENTER_LEFT; -fx-padding: 4 8 4 4;");
            row.setMaxWidth(Double.MAX_VALUE);

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
                        && from < model.pages.size() && to < model.pages.size()) {
                    PageSource moved = model.pages.remove(from);
                    model.pages.add(to, moved);
                }
                e.setDropCompleted(true);
                e.consume();
            });
        }

        @Override
        protected void updateItem(PageSource src, boolean empty) {
            super.updateItem(src, empty);
            setText(null);
            if (empty || src == null) { setGraphic(null); return; }

            String name = switch (src) {
                case PageSource.PdfPageSource ps -> "📄 " + ps.file().getFileName()
                    + (ps.range().isAll() ? " (all pages)" : " pages " + ps.range().pageNumbers());
                case PageSource.ImageSource is -> "🖼 " + is.file().getFileName();
            };
            nameLabel.setText(name);

            upBtn.setOnAction(e -> {
                int idx = model.pages.indexOf(src);
                if (idx > 0) { model.pages.remove(idx); model.pages.add(idx - 1, src); }
            });
            downBtn.setOnAction(e -> {
                int idx = model.pages.indexOf(src);
                if (idx < model.pages.size() - 1) {
                    model.pages.remove(idx);
                    model.pages.add(idx + 1, src);
                }
            });
            removeBtn.setOnAction(e -> model.pages.remove(src));

            setGraphic(row);
        }
    }
}
