package org.example.app.gui.panels;

import javafx.concurrent.Task;
import javafx.scene.layout.VBox;
import org.example.core.convert.DocumentConverter;
import org.example.core.model.*;
import org.example.core.operations.PdfMerger;
import org.example.app.i18n.I18n;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class MergePanel extends BasePanel {

    public MergePanel() { super("panel.MERGE.title", "run.MERGE", "_merged"); }

    @Override
    protected VBox buildOptionsArea() { return new VBox(); }

    @Override
    protected void onRun() {
        List<Path> files = List.copyOf(fileList.getFiles());
        if (files.isEmpty()) return;

        Path defaultOut = files.get(0).resolveSibling(
            stripExt(files.get(0).getFileName().toString()) + "_merged.pdf");
        Path output = resolveOutput(defaultOut);

        Task<MergeResult> task = new Task<>() {
            @Override
            protected MergeResult call() throws Exception {
                updateMessage(I18n.t("msg.merging", files.size()));
                List<Path> temps = new ArrayList<>();
                try {
                    List<PageSource> sources = new ArrayList<>();
                    for (Path p : files) {
                        // PDFs and images go in directly; documents are converted to PDF.
                        if (DocumentConverter.classify(p) == DocumentConverter.Kind.IMAGE) {
                            sources.add(new PageSource.ImageSource(p, PageSize.FIT));
                        } else {
                            Path pdf = DocumentConverter.ensurePdf(p, PageSize.FIT, temps);
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
