package org.example.app.gui.panels;

import javafx.concurrent.Task;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.example.app.gui.component.ProgressPanel;
import org.example.core.model.CompressOptions;
import org.example.core.model.CompressResult;
import org.example.core.operations.PdfCompressor;
import org.example.core.service.OperationRunner;
import org.example.core.service.OperationType;
import org.example.app.i18n.I18n;

import java.nio.file.Path;
import java.util.List;

public class CompressPanel extends BasePanel {

    private TextField sizeField;
    private ComboBox<String> unitBox;

    public CompressPanel() { super("panel.COMPRESS.title", "run.COMPRESS", OperationType.COMPRESS); }

    @Override
    protected boolean supportsBatch() { return true; }

    @Override
    protected String inputHintKey() {
        return "hint.COMPRESS";
    }

    @Override
    protected VBox buildOptionsArea() {
        Label label = new Label();
        I18n.bindText(label::setText, "compress.target.label");
        label.getStyleClass().add("text-sm");
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
                updateMessage(I18n.t("msg.compressing"));
                return OperationRunner.run(input, output,
                    (pdf, out) -> PdfCompressor.execute(new CompressOptions(pdf, targetBytes, out)));
            }
        };
        progressPanel.run(task, output, r -> r.targetReached() ? null
            : I18n.t("compress.warn",
                ProgressPanel.humanSize(r.resultBytes()),
                ProgressPanel.humanSize(r.originalBytes())));
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
