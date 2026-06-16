package org.example.app.gui.panels;

import javafx.concurrent.Task;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.example.core.model.ImageToPdfOptions;
import org.example.core.model.PageSize;
import org.example.core.model.PdfResult;
import org.example.core.operations.ImageToPdfConverter;

import java.nio.file.Path;
import java.util.List;

public class ImagesToPdfPanel extends BasePanel {

    private ComboBox<PageSize> pageSizeBox;

    public ImagesToPdfPanel() { super("Images → PDF", "▶  Convert"); }

    @Override
    protected VBox buildOptionsArea() {
        Label label = new Label("Page size:");
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

        Task<PdfResult> task = new Task<>() {
            @Override
            protected PdfResult call() throws Exception {
                updateMessage("Converting " + files.size() + " image(s)…");
                return ImageToPdfConverter.execute(new ImageToPdfOptions(files, size, output));
            }
        };
        progressPanel.run(task, output);
    }

    private static String stripExt(String n) {
        int d = n.lastIndexOf('.');
        return d >= 0 ? n.substring(0, d) : n;
    }
}
