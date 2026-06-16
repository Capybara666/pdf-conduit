package org.example.app.gui.wizard;

import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.example.app.gui.component.DragReorder;
import org.example.core.model.PageSource;

public class Step2ArrangePages implements WizardStep {

    private final WizardModel model;

    public Step2ArrangePages(WizardModel model) { this.model = model; }

    @Override
    public Node getContent() {
        Label title = new Label("Step 2: Arrange pages");
        title.getStyleClass().add("panel-title");
        Label hint = new Label("Drag rows to reorder. Right-click for more.");
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
        private final Label iconLabel  = new Label();
        private final Label nameLabel  = new Label();
        private final MenuItem moveUpItem   = new MenuItem("Move up");
        private final MenuItem moveDownItem = new MenuItem("Move down");
        private final HBox row;

        DraggablePageCell(WizardModel model) {
            this.model = model;

            dragHandle.setStyle("-fx-font-size: 10px; -fx-opacity: 0.35;");
            nameLabel.setStyle("-fx-font-size: 11px;");

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            row = new HBox(6, dragHandle, iconLabel, nameLabel, spacer);
            row.getStyleClass().add("file-list-item");
            row.setMaxWidth(Double.MAX_VALUE);

            setContextMenu(new ContextMenu(moveUpItem, moveDownItem));
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);

            DragReorder.install(this, model.pages);
        }

        @Override
        protected void updateItem(PageSource src, boolean empty) {
            super.updateItem(src, empty);
            setText(null);
            if (empty || src == null) { setGraphic(null); return; }

            iconLabel.setText(src instanceof PageSource.PdfPageSource ? "📄" : "🖼");
            nameLabel.setText(switch (src) {
                case PageSource.PdfPageSource ps -> ps.file().getFileName()
                    + (ps.range().isAll() ? " (all pages)" : " pages " + ps.range().pageNumbers());
                case PageSource.ImageSource is -> is.file().getFileName().toString();
            });

            moveUpItem.setOnAction(e -> {
                int idx = model.pages.indexOf(src);
                if (idx > 0) { model.pages.remove(idx); model.pages.add(idx - 1, src); }
            });
            moveDownItem.setOnAction(e -> {
                int idx = model.pages.indexOf(src);
                if (idx < model.pages.size() - 1) {
                    model.pages.remove(idx);
                    model.pages.add(idx + 1, src);
                }
            });

            setGraphic(row);
        }
    }
}
