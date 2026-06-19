package org.example.app.gui.component;

import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.example.app.gui.util.PdfThumbnails;
import org.example.app.i18n.I18n;
import org.example.core.convert.DocumentConverter;
import org.example.core.model.PageSize;
import org.example.core.util.PageRangeFormatter;
import org.example.core.util.PageRangeParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Modal dialog that lets the user pick pages visually from a file's thumbnails.
 * Returns the chosen pages as a compact range expression (e.g. {@code "1-3,5"}),
 * or {@code ""} when every page is selected (the "all pages" convention). The
 * caller seeds it with the current expression and writes the result back into
 * its page field, so the text field stays the source of truth. Used by Extract
 * and Rotate, which still support typing the range directly.
 */
public final class PageSelectDialog {

    private static final int THUMB_DPI = 42;

    private PageSelectDialog() {}

    /** Shows the picker for {@code file}; returns the chosen expression, or empty if cancelled. */
    public static Optional<String> choose(Window owner, Path file, String currentExpr) {
        PageSelectGrid grid = new PageSelectGrid();

        Dialog<String> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(I18n.t("select.dialog.title"));
        dialog.setHeaderText(I18n.t("select.dialog.header", file.getFileName()));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
        Button okBtn = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        okBtn.setDisable(true);

        // Toolbar: select all / none / invert + a live count.
        Label count = new Label();
        Button all = secondary(I18n.t("select.all"));
        Button none = secondary(I18n.t("select.none"));
        Button invert = secondary(I18n.t("select.invert"));
        all.setOnAction(e -> grid.selectAll());
        none.setOnAction(e -> grid.selectNone());
        invert.setOnAction(e -> grid.invert());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(8, all, none, invert, spacer, count);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setVisible(false);

        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setMaxSize(48, 48);
        StackPane center = new StackPane(spinner);
        center.setPrefSize(560, 420);

        VBox content = new VBox(10, toolbar, center);
        content.setPadding(new Insets(6));
        VBox.setVgrow(center, Priority.ALWAYS);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefSize(620, 540);
        if (owner != null && owner.getScene() != null) {
            dialog.getDialogPane().getStylesheets().addAll(owner.getScene().getStylesheets());
        }

        Runnable refresh = () -> {
            int sel = grid.selectedCount();
            count.setText(I18n.t("select.count", sel, grid.total()));
            okBtn.setDisable(sel == 0);
        };
        grid.setOnChange(refresh);

        // Render thumbnails off the FX thread; the modal dialog's nested event
        // loop still delivers these callbacks on the FX thread.
        Task<List<Image>> task = renderTask(file);
        task.setOnSucceeded(e -> {
            center.getChildren().setAll(grid);
            grid.setPages(task.getValue());
            grid.setSelection(seed(currentExpr, grid.total()));
            toolbar.setVisible(true);
            refresh.run();
        });
        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            Label error = new Label(I18n.t("select.failed", ex == null ? "?" : ex.getMessage()));
            error.setWrapText(true);
            error.getStyleClass().add("error-banner");
            center.getChildren().setAll(error);
        });
        Thread t = new Thread(task, "page-select-render");
        t.setDaemon(true);
        t.start();

        dialog.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            if (grid.selectedCount() == grid.total()) return "";   // all → blank (= all pages)
            return PageRangeFormatter.format(grid.selected());
        });

        return dialog.showAndWait();
    }

    private static Task<List<Image>> renderTask(Path file) {
        return new Task<>() {
            @Override
            protected List<Image> call() throws Exception {
                List<Path> temps = new ArrayList<>();
                try {
                    Path pdf = DocumentConverter.ensurePdf(file, PageSize.FIT, temps);
                    return PdfThumbnails.render(pdf, THUMB_DPI, null);
                } finally {
                    for (Path p : temps) {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                    }
                }
            }
        };
    }

    /** The set of pages to pre-select from {@code expr} (blank or invalid → all). */
    private static Set<Integer> seed(String expr, int total) {
        if (expr != null && !expr.isBlank()) {
            try {
                return new HashSet<>(PageRangeParser.parse(expr, total).pageNumbers());
            } catch (Exception ignored) {
                // fall through to "all"
            }
        }
        Set<Integer> all = new HashSet<>();
        for (int i = 1; i <= total; i++) all.add(i);
        return all;
    }

    private static Button secondary(String text) {
        Button b = new Button(text);
        b.getStyleClass().add("btn-secondary");
        b.setMinWidth(Region.USE_PREF_SIZE);
        return b;
    }
}
