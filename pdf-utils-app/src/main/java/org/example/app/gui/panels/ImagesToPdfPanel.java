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
        Path output = resolveOutput(
            files.get(0).resolveSibling(
                stripExt(files.get(0).getFileName().toString()) + "_converted.pdf"));

        Task<MergeResult> task = new Task<>() {
            @Override
            protected MergeResult call() throws Exception {
                updateMessage(I18n.t("msg.converting", files.size()));
                List<Path> temps = new ArrayList<>();
                try {
                    List<PageSource> sources = new ArrayList<>();
                    for (Path p : files) {
                        // Images get the chosen page size; PDFs and documents are
                        // included/converted as page sources.
                        if (DocumentConverter.classify(p) == DocumentConverter.Kind.IMAGE) {
                            sources.add(new PageSource.ImageSource(p, size));
                        } else {
                            Path pdf = DocumentConverter.ensurePdf(p, size, temps);
                            sources.add(new PageSource.PdfPageSource(pdf, PageRange.ALL));
                        }
                    }
                    return PdfMerger.execute(new MergeOptions(sources, output));
                } finally {
                    for (Path t : temps) Files.deleteIfExists(t);
                }
            }
        };
        progressPanel.run(task, output);
    }
}
