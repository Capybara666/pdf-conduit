package com.pdfconduit.app.gui.wizard;

import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import com.pdfconduit.app.gui.component.DragReorder;
import com.pdfconduit.app.gui.component.PageSelectDialog;
import com.pdfconduit.app.i18n.I18n;
import com.pdfconduit.core.exception.InvalidPageRangeException;
import com.pdfconduit.core.model.PageRange;
import com.pdfconduit.core.model.PageSource;
import com.pdfconduit.core.util.PageRangeFormatter;
import com.pdfconduit.core.util.PageRangeParser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class Step2ArrangePages implements WizardStep {

    private final WizardModel model;
    private final Map<Path, Integer> pageCounts = new HashMap<>();
    private Node content;

    public Step2ArrangePages(WizardModel model) { this.model = model; }

    @Override
    public Node getContent() {
        if (content == null) content = build();
        return content;
    }

    private Node build() {
        Label title = new Label();
        I18n.bindText(title::setText, "wizard.step2.title");
        title.getStyleClass().add("panel-title");
        Label hint = new Label();
        I18n.bindText(hint::setText, "wizard.step2.hint");
        hint.getStyleClass().add("text-caption");

        ListView<PageSource> listView = new ListView<>(model.pages);
        listView.getStyleClass().add("file-list-view");
        listView.setCellFactory(lv -> new DraggablePageCell());
        // Re-render the rows (their per-page "pages" link text) in the new language.
        I18n.addListener(listView::refresh);

        VBox top = new VBox(6, title, hint);
        BorderPane root = new BorderPane();
        root.getStyleClass().add("wizard-step");
        root.setTop(top);
        root.setCenter(listView);
        BorderPane.setMargin(listView, new Insets(10, 0, 0, 0));
        return root;
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
        dialog.setTitle(I18n.t("wizard.pages.dialog.title"));
        dialog.setHeaderText(I18n.t("wizard.pages.for", src.file().getFileName())
            + (total > 0 ? " " + I18n.t("wizard.pages.count", total) : ""));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);

        TextField field = new TextField(src.range().isAll() ? "" : describe(src.range()));
        field.setPromptText(I18n.t("wizard.pages.field.prompt"));
        Label error = new Label();
        error.getStyleClass().add("text-error");
        error.setVisible(false);
        Button pick = new Button(I18n.t("select.pick"));
        pick.getStyleClass().add("btn-secondary");
        pick.setOnAction(e -> PageSelectDialog.choose(owner, src.file(), field.getText())
            .ifPresent(field::setText));
        VBox content = new VBox(8, new Label(I18n.t("wizard.pages.field.label")), field, pick, error);
        content.getStyleClass().add("pad-sm");
        dialog.getDialogPane().setContent(content);

        int max = total > 0 ? total : Integer.MAX_VALUE;
        Button okBtn = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        okBtn.addEventFilter(ActionEvent.ACTION, ev -> {
            String expr = field.getText().strip();
            if (expr.isEmpty()) return;
            try {
                PageRangeParser.parse(expr, max);
            } catch (InvalidPageRangeException ex) {
                error.setText(I18n.t("wizard.pages.invalid")
                    + (total > 0 ? I18n.t("wizard.pages.invalid.max", total) : ""));
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
        return range.isAll() ? I18n.t("wizard.pages.all")
                             : PageRangeFormatter.format(range.pageNumbers());
    }

    private class DraggablePageCell extends ListCell<PageSource> {
        private final Label dragHandle = new Label("⠿");
        private final Label iconLabel  = new Label();
        private final Label nameLabel  = new Label();
        private final Hyperlink pagesLink = new Hyperlink();
        private final MenuItem moveUpItem   = new MenuItem();
        private final MenuItem moveDownItem = new MenuItem();
        private final HBox row;

        DraggablePageCell() {
            I18n.bindText(moveUpItem::setText, "wizard.moveup");
            I18n.bindText(moveDownItem::setText, "wizard.movedown");
            dragHandle.getStyleClass().add("text-handle");
            nameLabel.getStyleClass().add("text-sm");
            pagesLink.getStyleClass().add("text-sm");
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
                pagesLink.setText(I18n.t("wizard.pages.link", describe(ps.range())));
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
