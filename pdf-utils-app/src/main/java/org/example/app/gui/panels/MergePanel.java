package org.example.app.gui.panels;

import javafx.concurrent.Task;
import javafx.scene.layout.VBox;
import org.example.core.model.*;
import org.example.core.operations.PdfMerger;
import org.example.core.util.FileTypeDetector;

import java.nio.file.Path;
import java.util.List;

public class MergePanel extends BasePanel {

    public MergePanel() { super("Merge PDFs / Images", "▶  Merge", "_merged"); }

    @Override
    protected VBox buildOptionsArea() { return new VBox(); }

    @Override
    protected void onRun() {
        List<Path> files = List.copyOf(fileList.getFiles());
        if (files.isEmpty()) return;

        Path defaultOut = files.get(0).resolveSibling(
            stripExt(files.get(0).getFileName().toString()) + "_merged.pdf");
        Path output = resolveOutput(defaultOut);

        List<PageSource> sources = files.stream()
            .map(p -> FileTypeDetector.isPdf(p)
                ? (PageSource) new PageSource.PdfPageSource(p, PageRange.ALL)
                : new PageSource.ImageSource(p, PageSize.FIT))
            .toList();

        Task<MergeResult> task = new Task<>() {
            @Override
            protected MergeResult call() throws Exception {
                updateMessage("Merging " + files.size() + " file(s)…");
                return PdfMerger.execute(new MergeOptions(sources, output));
            }
        };
        progressPanel.run(task, output);
    }
}
