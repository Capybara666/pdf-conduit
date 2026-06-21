package com.pdfconduit.app.gui.panels;

import javafx.concurrent.Task;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import com.pdfconduit.app.gui.Ui;
import com.pdfconduit.app.gui.component.ProgressPanel;
import com.pdfconduit.app.gui.util.Settings;
import com.pdfconduit.core.model.CompressOptions;
import com.pdfconduit.core.model.CompressResult;
import com.pdfconduit.core.operations.PdfCompressor;
import com.pdfconduit.core.service.OperationRunner;
import com.pdfconduit.core.service.OperationType;
import com.pdfconduit.app.i18n.I18n;

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
        double value = Settings.compressValue();
        sizeField = new TextField(value == Math.rint(value)
            ? Long.toString((long) value) : Double.toString(value));
        unitBox = new ComboBox<>();
        unitBox.getItems().addAll("MB", "KB");
        unitBox.setValue(Settings.compressUnit());
        HBox row = new HBox(Ui.INLINE_GAP, sizeField, unitBox);
        return new VBox(Ui.LABEL_FIELD_GAP, fieldLabel("compress.target.label"), row);
    }

    @Override
    protected void onRun() {
        List<Path> files = List.copyOf(fileList.getFiles());
        if (files.isEmpty()) return;
        long targetBytes = parseTargetBytes();
        if (targetBytes <= 0) return;

        if (isBatchMode()) {
            runPerFile("verb.compress", (in, out) ->
                PdfCompressor.execute(new CompressOptions(in, targetBytes, out)));
            return;
        }

        Path input = files.get(0);
        Path output = confirmOutputFor(input).orElse(null);
        if (output == null) return;

        Task<CompressResult> task = new Task<>() {
            @Override
            protected CompressResult call() throws Exception {
                updateMessage(I18n.t("msg.busy", I18n.t("verb.compress")));
                return OperationRunner.run(input, output,
                    (pdf, out) -> PdfCompressor.execute(new CompressOptions(pdf, targetBytes, out)));
            }
        };
        progressPanel.run(task, output,
            r -> r.targetReached() ? null
                : I18n.t("compress.warn",
                    ProgressPanel.humanSize(r.resultBytes()),
                    ProgressPanel.humanSize(r.originalBytes())),
            CompressPanel::summarize);
    }

    /** "12.0 MB → 3.2 MB (−73%)" — the actual reduction achieved. */
    private static String summarize(CompressResult r) {
        long orig = r.originalBytes(), res = r.resultBytes();
        int pct = orig > 0 ? (int) Math.round((1.0 - (double) res / orig) * 100) : 0;
        return I18n.t("compress.summary",
            ProgressPanel.humanSize(orig), ProgressPanel.humanSize(res), pct);
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
