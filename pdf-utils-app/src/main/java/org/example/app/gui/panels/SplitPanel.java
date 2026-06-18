package org.example.app.gui.panels;

import javafx.concurrent.Task;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import org.example.core.convert.DocumentConverter;
import org.example.core.model.PageRange;
import org.example.core.model.PageSize;
import org.example.core.model.SplitOptions;
import org.example.core.model.SplitResult;
import org.example.core.operations.PdfSplitter;
import org.example.core.util.PageRangeParser;
import org.example.app.i18n.I18n;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class SplitPanel extends BasePanel {

    private TextField pagesField;

    public SplitPanel() { super(I18n.t("panel.SPLIT.title"), I18n.t("run.SPLIT"), "_extracted"); }

    @Override
    protected boolean supportsBatch() { return true; }

    @Override
    protected String inputHint() {
        return I18n.t("hint.SPLIT");
    }

    @Override
    protected VBox buildOptionsArea() {
        Label label = new Label(I18n.t("split.pages.label"));
        label.setStyle("-fx-font-size: 11px;");
        pagesField = new TextField();
        pagesField.setPromptText(I18n.t("split.pages.prompt"));
        return new VBox(4, label, pagesField);
    }

    @Override
    protected void onRun() {
        List<Path> files = List.copyOf(fileList.getFiles());
        if (files.isEmpty()) return;
        String pagesExpr = pagesField.getText();

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
