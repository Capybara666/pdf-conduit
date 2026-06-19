package org.example.app.gui.panels;

import javafx.concurrent.Task;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.example.core.convert.DocumentConverter;
import org.example.core.model.MergeOptions;
import org.example.core.model.MergeResult;
import org.example.core.model.PageRange;
import org.example.core.model.PageSize;
import org.example.core.model.PageSource;
import org.example.core.operations.PdfMerger;
import org.example.app.i18n.I18n;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ImagesToPdfPanel extends BasePanel {

    private ComboBox<PageSize> pageSizeBox;

    public ImagesToPdfPanel() { super(I18n.t("panel.IMAGES.title"), I18n.t("run.IMAGES"), "_converted"); }

    /** Each input becomes its own PDF; combining is the Merge operation's job. */
    @Override
    protected boolean supportsBatch() { return true; }

    @Override
    protected VBox buildOptionsArea() {
        Label label = new Label(I18n.t("images.pagesize.label"));
        label.setStyle("-fx-font-size: 11px;");
        pageSizeBox = new ComboBox<>();
        pageSizeBox.getItems().addAll(PageSize.values());
        pageSizeBox.setValue(PageSize.FIT);
        HBox row = new HBox(8, label, pageSizeBox);
        return new VBox(4, row);
    }

    @Override
    protected void onRun() {
        List<Path> files = List.copyOf(fileList.getFiles());
        if (files.isEmpty()) return;
        PageSize size = pageSizeBox.getValue();

        if (isBatchMode()) {
            String dirText = outputField.getText();
            if (dirText == null || dirText.isBlank()) return;
            Path dir = Path.of(dirText);
            Task<Path> task = new Task<>() {
                @Override
                protected Path call() throws Exception {
                    Files.createDirectories(dir);
                    for (int i = 0; i < files.size(); i++) {
                        Path in = files.get(i);
                        updateMessage(I18n.t("msg.converting", 1) + " " + (i + 1) + "/" + files.size());
                        Path out = dir.resolve(
                            stripExt(in.getFileName().toString()) + "_converted.pdf");
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
        Path output = resolveOutput(
            input.resolveSibling(stripExt(input.getFileName().toString()) + "_converted.pdf"));
        Task<MergeResult> task = new Task<>() {
            @Override
            protected MergeResult call() throws Exception {
                updateMessage(I18n.t("msg.converting", 1));
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
