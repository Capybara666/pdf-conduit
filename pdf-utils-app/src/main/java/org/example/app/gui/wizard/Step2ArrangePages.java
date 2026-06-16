package org.example.app.gui.wizard;

import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.example.app.gui.component.DragReorder;
import org.example.core.exception.InvalidPageRangeException;
import org.example.core.model.PageRange;
import org.example.core.model.PageSource;
import org.example.core.util.PageRangeParser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Step2ArrangePages implements WizardStep {

    private final WizardModel model;
    private final Map<Path, Integer> pageCounts = new HashMap<>();

    public Step2ArrangePages(WizardModel model) { this.model = model; }

    @Override
    public Node getContent() {
        Label title = new Label("Step 2: Arrange pages");
        title.getStyleClass().add("panel-title");
        Label hint = new Label("Drag rows to reorder. Click a PDF's page link to choose pages.");
        hint.setStyle("-fx-font-size: 11px; -fx-opacity: 0.6;");

        ListView<PageSource> listView = new ListView<>(model.pages);
        listView.getStyleClass().add("file-list-view");
        listView.setCellFactory(lv -> new DraggablePageCell());
        VBox.setVgrow(listView, Priority.ALWAYS);

        VBox box = new VBox(10, title, hint, listView);
        box.setStyle("-fx-padding: 18;");
        return box;
    }

    /** Page count of a PDF, loaded once and cached. Returns 0 if it cannot be read. */
    private int pageCount(Path file) {
        return pageCounts.computeIfAbsent(file, f -> {
            try (PDDocument doc = Loader.loadPDF(f.toFile())) {
                return doc.getNumberOfPages();
            } catch (IOException e) {
                return 0;
            }
        });
    }

    private void editPages(PageSource.PdfPageSource src, Window owner) {
        int total = pageCount(src.file());

        Dialog<PageRange> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Choose pages");
        dialog.setHeaderText("Pages for " + src.file().getFileName()
            + (total > 0 ? " (" + total + " pages)" : ""));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);

        TextField field = new TextField(src.range().isAll() ? "" : describe(src.range()));
        field.setPromptText("e.g. 1-3,5");
        Label error = new Label();
        error.setStyle("-fx-text-fill: #ef4444; -fx-font-size: 11px;");
        error.setVisible(false);
        VBox content = new VBox(8, new Label("Pages (blank = all):"), field, error);
        content.setStyle("-fx-padding: 6;");
        dialog.getDialogPane().setContent(content);

        int max = total > 0 ? total : Integer.MAX_VALUE;
        Button okBtn = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        okBtn.addEventFilter(ActionEvent.ACTION, ev -> {
            String expr = field.getText().strip();
            if (expr.isEmpty()) return;
            try {
                PageRangeParser.parse(expr, max);
            } catch (InvalidPageRangeException ex) {
                error.setText("Invalid range. Use e.g. 1-3,5"
                    + (total > 0 ? " (pages 1–" + total + ")" : ""));
                error.setVisible(true);
                ev.consume();
            }
        });

        dialog.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            String expr = field.getText().strip();
            if (expr.isEmpty()) return PageRange.ALL;
            try {
                return PageRangeParser.parse(expr, max);
            } catch (InvalidPageRangeException ex) {
                return null;
            }
        });

        dialog.showAndWait().ifPresent(range -> {
            int idx = model.pages.indexOf(src);
            if (idx >= 0) {
                model.pages.set(idx, new PageSource.PdfPageSource(src.file(), range));
            }
        });
    }

    /** Compact display of a page range, e.g. "all" or "1-3,5,8-9". */
    private static String describe(PageRange range) {
        if (range.isAll()) return "all";
        List<Integer> nums = range.pageNumbers();
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < nums.size()) {
            int start = nums.get(i);
            int end = start;
            while (i + 1 < nums.size() && nums.get(i + 1) == end + 1) end = nums.get(++i);
            if (sb.length() > 0) sb.append(",");
            sb.append(end > start ? start + "-" + end : String.valueOf(start));
            i++;
        }
        return sb.toString();
    }

    private class DraggablePageCell extends ListCell<PageSource> {
        private final Label dragHandle = new Label("⠿");
        private final Label iconLabel  = new Label();
        private final Label nameLabel  = new Label();
        private final Hyperlink pagesLink = new Hyperlink();
        private final MenuItem moveUpItem   = new MenuItem("Move up");
        private final MenuItem moveDownItem = new MenuItem("Move down");
        private final HBox row;

        DraggablePageCell() {
            dragHandle.setStyle("-fx-font-size: 10px; -fx-opacity: 0.35;");
            nameLabel.setStyle("-fx-font-size: 11px;");
            pagesLink.setStyle("-fx-font-size: 11px;");
            pagesLink.managedProperty().bind(pagesLink.visibleProperty());

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            row = new HBox(6, dragHandle, iconLabel, nameLabel, spacer, pagesLink);
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
                case PageSource.PdfPageSource ps -> ps.file().getFileName().toString();
                case PageSource.ImageSource is   -> is.file().getFileName().toString();
            });

            if (src instanceof PageSource.PdfPageSource ps) {
                pagesLink.setVisible(true);
                pagesLink.setText("Pages: " + describe(ps.range()) + "  ✎");
                pagesLink.setOnAction(e -> editPages(ps, getScene().getWindow()));
            } else {
                pagesLink.setVisible(false);
                pagesLink.setOnAction(null);
            }

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
