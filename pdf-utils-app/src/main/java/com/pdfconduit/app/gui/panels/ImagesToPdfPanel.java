package com.pdfconduit.app.gui.panels;

import javafx.concurrent.Task;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import com.pdfconduit.core.convert.DocumentConverter;
import com.pdfconduit.core.model.MergeOptions;
import com.pdfconduit.core.model.MergeResult;
import com.pdfconduit.core.model.PageRange;
import com.pdfconduit.core.model.PageSize;
import com.pdfconduit.core.model.PageSource;
import com.pdfconduit.app.gui.Ui;
import com.pdfconduit.core.operations.PdfMerger;
import com.pdfconduit.core.service.OperationRunner;
import com.pdfconduit.core.service.OperationType;
import com.pdfconduit.app.i18n.I18n;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ImagesToPdfPanel extends BasePanel {

    private ComboBox<PageSize> pageSizeBox;

    public ImagesToPdfPanel() { super("panel.IMAGES.title", "run.IMAGES", OperationType.IMAGES_TO_PDF); }

    /** Each input becomes its own PDF; combining is the Merge operation's job. */
    @Override
    protected boolean supportsBatch() { return true; }

    @Override
    protected String inputHintKey() { return "hint.IMAGES"; }

    @Override
    protected VBox buildOptionsArea() {
        pageSizeBox = new ComboBox<>();
        pageSizeBox.getItems().addAll(PageSize.values());
        pageSizeBox.setValue(PageSize.FIT);
        HBox row = new HBox(Ui.INLINE_GAP, fieldLabel("images.pagesize.label"), pageSizeBox);
        return new VBox(Ui.OPTION_GAP, row);
    }

    @Override
    protected void onRun() {
        List<Path> files = List.copyOf(fileList.getFiles());
        if (files.isEmpty()) return;
        PageSize size = pageSizeBox.getValue();

        if (isBatchMode()) {
            Path dir = outputDir();
            Task<Path> task = new Task<>() {
                @Override
                protected Path call() throws Exception {
                    Files.createDirectories(dir);
                    for (int i = 0; i < files.size(); i++) {
                        Path in = files.get(i);
                        updateMessage(I18n.t("msg.busy.count", I18n.t("verb.convert"), i + 1, files.size()));
                        Path out = dir.resolve(OperationRunner.outputName(operationType(), in));
                        convertOne(in, size, out);
                        updateProgress(i + 1, files.size());
                    }
                    return dir;
                }
            };
            progressPanel.run(task, dir);
            return;
        }

        // Single input -> one PDF.
        Path input = files.get(0);
        Path output = resolveOutputFor(input);
        Task<MergeResult> task = new Task<>() {
            @Override
            protected MergeResult call() throws Exception {
                updateMessage(I18n.t("msg.busy", I18n.t("verb.convert")));
                return convertOne(input, size, output);
            }
        };
        progressPanel.run(task, output);
    }

    /** Converts a single input file to its own PDF (images use {@code size}). */
    private static MergeResult convertOne(Path in, PageSize size, Path out) throws Exception {
        List<Path> temps = new ArrayList<>();
        try {
            PageSource source;
            if (DocumentConverter.classify(in) == DocumentConverter.Kind.IMAGE) {
                source = new PageSource.ImageSource(in, size);
            } else {
                Path pdf = DocumentConverter.ensurePdf(in, size, temps);
                source = new PageSource.PdfPageSource(pdf, PageRange.ALL);
            }
            return PdfMerger.execute(new MergeOptions(List.of(source), out));
        } finally {
            for (Path t : temps) Files.deleteIfExists(t);
        }
    }
}
