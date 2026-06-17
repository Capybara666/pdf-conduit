package org.example.app.gui.panels;

import javafx.concurrent.Task;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.example.app.gui.component.ProgressPanel;
import org.example.core.model.CompressOptions;
import org.example.core.model.CompressResult;
import org.example.core.operations.PdfCompressor;

import java.nio.file.Path;
import java.util.List;

public class CompressPanel extends BasePanel {

    private TextField sizeField;
    private ComboBox<String> unitBox;

    public CompressPanel() { super("Compress PDF", "▶  Compress", "_compressed"); }

    @Override
    protected boolean supportsBatch() { return true; }

    @Override
    protected String inputHint() {
        return "Add several PDFs to compress each to the target size into a folder.";
    }

    @Override
    protected VBox buildOptionsArea() {
        Label label = new Label("Target size:");
        label.setStyle("-fx-font-size: 11px;");
        sizeField = new TextField("5");
        unitBox = new ComboBox<>();
        unitBox.getItems().addAll("MB", "KB");
        unitBox.setValue("MB");
        HBox row = new HBox(6, sizeField, unitBox);
        return new VBox(4, label, row);
    }

    @Override
    protected void onRun() {
        List<Path> files = List.copyOf(fileList.getFiles());
        if (files.isEmpty()) return;
        long targetBytes = parseTargetBytes();
        if (targetBytes <= 0) return;

        if (isBatchMode()) {
            runPerFile("Compressing", (in, out) ->
                PdfCompressor.execute(new CompressOptions(in, targetBytes, out)));
            return;
        }

        Path input = files.get(0);
        Path output = resolveOutput(input.resolveSibling(
            stripExt(input.getFileName().toString()) + "_compressed.pdf"));

        Task<CompressResult> task = new Task<>() {
            @Override
            protected CompressResult call() throws Exception {
                updateMessage("Compressing…");
                return PdfCompressor.execute(new CompressOptions(input, targetBytes, output));
            }
        };
        progressPanel.run(task, output, r -> r.targetReached() ? null
            : "Could not reach the target size. Smallest achievable was "
              + ProgressPanel.humanSize(r.resultBytes())
              + " (original " + ProgressPanel.humanSize(r.originalBytes()) + ").");
    }

    private long parseTargetBytes() {
        try {
            double val = Double.parseDouble(sizeField.getText().strip());
            return unitBox.getValue().equals("MB")
                ? (long)(val * 1024 * 1024)
                : (long)(val * 1024);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

}
