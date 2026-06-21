package com.pdfconduit.app.gui.panels;

import javafx.beans.binding.Bindings;
import javafx.concurrent.Task;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import com.pdfconduit.app.gui.component.PageSelectDialog;
import com.pdfconduit.core.convert.DocumentConverter;
import com.pdfconduit.core.model.PageRange;
import com.pdfconduit.core.model.PageSize;
import com.pdfconduit.core.model.SplitMode;
import com.pdfconduit.core.model.SplitOptions;
import com.pdfconduit.core.model.SplitResult;
import com.pdfconduit.core.operations.PdfSplitter;
import com.pdfconduit.core.service.OperationRunner;
import com.pdfconduit.core.service.OperationType;
import com.pdfconduit.core.util.PageRangeParser;
import com.pdfconduit.app.gui.Ui;
import com.pdfconduit.app.i18n.I18n;

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
    private ToggleGroup modeGroup;
    private RadioButton separateRadio;

    public SplitPanel() { super("panel.SPLIT.title", "run.SPLIT", OperationType.EXTRACT); }

    @Override
    protected boolean supportsBatch() { return true; }

    @Override
    protected String inputHintKey() {
        return "hint.SPLIT";
    }

    private SplitMode mode() {
        return (separateRadio != null && separateRadio.isSelected()) ? SplitMode.SEPARATE : SplitMode.COMBINE;
    }

    /** Separate-files mode always writes a folder, even for a single input. */
    @Override
    protected boolean folderOnly() {
        return mode() == SplitMode.SEPARATE;
    }

    @Override
    protected VBox buildOptionsArea() {
        pagesField = new TextField();
        I18n.bindText(pagesField::setPromptText, "split.pages.prompt");
        HBox.setHgrow(pagesField, Priority.ALWAYS);
        Button pick = new Button();
        I18n.bindText(pick::setText, "select.pick");
        pick.getStyleClass().add("btn-secondary");
        pick.disableProperty().bind(Bindings.isEmpty(fileList.getFiles()));
        pick.setOnAction(e -> pickPages());
        VBox pagesGroup = labeledField("split.pages.label", new HBox(Ui.INLINE_GAP, pagesField, pick));

        // A binary output choice — radio buttons show the full labels (no
        // truncation) and make the selection unambiguous.
        modeGroup = new ToggleGroup();
        RadioButton combineRadio = new RadioButton();
        I18n.bindText(combineRadio::setText, "split.mode.combine");
        combineRadio.setToggleGroup(modeGroup);
        combineRadio.setSelected(true);
        separateRadio = new RadioButton();
        I18n.bindText(separateRadio::setText, "split.mode.separate");
        separateRadio.setToggleGroup(modeGroup);
        // Switching mode flips the single file-name field on/off.
        modeGroup.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == null) a.setSelected(true);     // keep one always selected
            refreshOutputMode();
        });
        HBox modeRow = new HBox(Ui.INLINE_GAP, fieldLabel("split.mode.label"), combineRadio, separateRadio);
        modeRow.getStyleClass().add("row-left");

        return new VBox(Ui.OPTION_GAP, pagesGroup, modeRow);
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

        if (mode() == SplitMode.SEPARATE) {
            runSeparate(files, pagesExpr);
            return;
        }

        if (isBatchMode()) {
            runPerFile("verb.extract", (in, out) ->
                PdfSplitter.execute(new SplitOptions(in, resolveRange(pagesExpr, in), out)));
            return;
        }

        Path input = files.get(0);
        Path output = confirmOutputFor(input).orElse(null);
        if (output == null) return;
        Task<SplitResult> task = new Task<>() {
            @Override
            protected SplitResult call() throws Exception {
                updateMessage(I18n.t("msg.busy", I18n.t("verb.extract")));
                return OperationRunner.run(input, output,
                    (pdf, out) -> PdfSplitter.execute(
                        new SplitOptions(pdf, resolveRange(pagesExpr, pdf), out)));
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
                    updateMessage(I18n.t("msg.busy.count", I18n.t("verb.extract"), i + 1, files.size()));
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
