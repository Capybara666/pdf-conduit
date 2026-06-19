package org.example.app.gui.panels;

import javafx.beans.binding.Bindings;
import javafx.concurrent.Task;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import org.example.app.gui.component.PageSelectDialog;
import org.example.core.convert.DocumentConverter;
import org.example.core.model.PageRange;
import org.example.core.model.PageSize;
import org.example.core.model.SplitMode;
import org.example.core.model.SplitOptions;
import org.example.core.model.SplitResult;
import org.example.core.operations.PdfSplitter;
import org.example.core.util.PageRangeParser;
import org.example.app.i18n.I18n;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Extract / split pages. The page selection is the same either way; the output
 * mode decides whether the chosen pages are combined into one PDF or written one
 * file per page into the output folder.
 */
public class SplitPanel extends BasePanel {

    private TextField pagesField;
    private ComboBox<SplitMode> modeBox;

    public SplitPanel() { super(I18n.t("panel.SPLIT.title"), I18n.t("run.SPLIT"), "_extracted"); }

    @Override
    protected boolean supportsBatch() { return true; }

    @Override
    protected String inputHint() {
        return I18n.t("hint.SPLIT");
    }

    /** Separate-files mode always writes a folder, even for a single input. */
    @Override
    protected boolean folderOnly() {
        return modeBox != null && modeBox.getValue() == SplitMode.SEPARATE;
    }

    @Override
    protected VBox buildOptionsArea() {
        Label label = new Label(I18n.t("split.pages.label"));
        label.setStyle("-fx-font-size: 11px;");
        pagesField = new TextField();
        pagesField.setPromptText(I18n.t("split.pages.prompt"));
        HBox.setHgrow(pagesField, Priority.ALWAYS);
        Button pick = new Button(I18n.t("select.pick"));
        pick.getStyleClass().add("btn-secondary");
        pick.disableProperty().bind(Bindings.isEmpty(fileList.getFiles()));
        pick.setOnAction(e -> pickPages());

        Label modeLabel = new Label(I18n.t("split.mode.label"));
        modeLabel.setStyle("-fx-font-size: 11px;");
        modeBox = new ComboBox<>();
        modeBox.getItems().addAll(SplitMode.COMBINE, SplitMode.SEPARATE);
        modeBox.setValue(SplitMode.COMBINE);
        modeBox.setConverter(new StringConverter<>() {
            @Override public String toString(SplitMode m) {
                return m == SplitMode.SEPARATE ? I18n.t("split.mode.separate") : I18n.t("split.mode.combine");
            }
            @Override public SplitMode fromString(String s) { return null; }
        });
        // Switching to "separate files" drops the single file-name field.
        modeBox.valueProperty().addListener((o, a, b) -> refreshOutputMode());
        HBox modeRow = new HBox(8, modeLabel, modeBox);
        modeRow.setStyle("-fx-alignment: CENTER_LEFT;");

        return new VBox(8, new VBox(4, label, new HBox(6, pagesField, pick)), modeRow);
    }

    /** Opens the visual page picker for the first file and writes the result back. */
    private void pickPages() {
        if (fileList.getFiles().isEmpty()) return;
        PageSelectDialog.choose(getScene().getWindow(), fileList.getFiles().get(0),
            pagesField.getText()).ifPresent(pagesField::setText);
    }

    @Override
    protected void onRun() {
        List<Path> files = List.copyOf(fileList.getFiles());
        if (files.isEmpty()) return;
        String pagesExpr = pagesField.getText();

        if (modeBox.getValue() == SplitMode.SEPARATE) {
            runSeparate(files, pagesExpr);
            return;
        }

        if (isBatchMode()) {
            runPerFile("Extracting", (in, out) ->
                PdfSplitter.execute(new SplitOptions(in, resolveRange(pagesExpr, in), out)));
            return;
        }

        Path input = files.get(0);
        Path output = resolveOutput(input.resolveSibling(
            stripExt(input.getFileName().toString()) + "_extracted.pdf"));
        Task<SplitResult> task = new Task<>() {
            @Override
            protected SplitResult call() throws Exception {
                updateMessage(I18n.t("msg.extracting"));
                List<Path> temps = new ArrayList<>();
                try {
                    Path pdf = DocumentConverter.ensurePdf(input, PageSize.FIT, temps);
                    return PdfSplitter.execute(
                        new SplitOptions(pdf, resolveRange(pagesExpr, pdf), output));
                } finally {
                    for (Path t : temps) Files.deleteIfExists(t);
                }
            }
        };
        progressPanel.run(task, output);
    }

    /** Writes one PDF per selected page into the output folder, for each input file. */
    private void runSeparate(List<Path> files, String pagesExpr) {
        Path dir = outputDir();
        Task<Path> task = new Task<>() {
            @Override
            protected Path call() throws Exception {
                Files.createDirectories(dir);
                for (int i = 0; i < files.size(); i++) {
                    Path in = files.get(i);
                    updateMessage("Splitting " + (i + 1) + "/" + files.size() + "…");
                    List<Path> temps = new ArrayList<>();
                    try {
                        Path pdf = DocumentConverter.ensurePdf(in, PageSize.FIT, temps);
                        PdfSplitter.execute(new SplitOptions(
                            pdf, resolveRange(pagesExpr, pdf), SplitMode.SEPARATE, dir));
                    } catch (Exception ex) {
                        throw new Exception(in.getFileName() + ": " + ex.getMessage(), ex);
                    } finally {
                        for (Path t : temps) Files.deleteIfExists(t);
                    }
                    updateProgress(i + 1, files.size());
                }
                return dir;
            }
        };
        progressPanel.run(task, dir);
    }

    /** Parses a page expression against a PDF's actual page count (blank = all). */
    static PageRange resolveRange(String expr, Path pdf) throws Exception {
        if (expr == null || expr.isBlank()) return PageRange.ALL;
        int total;
        try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf.toFile())) {
            total = doc.getNumberOfPages();
        }
        return PageRangeParser.parse(expr, total);
    }
}
