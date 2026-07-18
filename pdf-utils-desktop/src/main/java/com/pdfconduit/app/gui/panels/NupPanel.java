package com.pdfconduit.app.gui.panels;

import javafx.concurrent.Task;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import com.pdfconduit.app.gui.Ui;
import com.pdfconduit.core.model.NupLayout;
import com.pdfconduit.core.model.NupOptions;
import com.pdfconduit.core.model.PdfResult;
import com.pdfconduit.core.operations.PdfNupImposer;
import com.pdfconduit.core.service.OperationRunner;
import com.pdfconduit.core.service.OperationType;
import com.pdfconduit.app.i18n.I18n;

import java.nio.file.Path;
import java.util.List;

/**
 * Imposes several pages per sheet (N-up) — 2/4/6/8/9-up grid presets — or, with the
 * booklet option, a saddle-stitch booklet. One imposed PDF per input; batch-capable.
 */
public class NupPanel extends BasePanel {

    private ComboBox<NupLayout> layoutBox;
    private CheckBox bookletCheck;

    public NupPanel() { super("panel.NUP.title", "run.NUP", OperationType.NUP); }

    @Override
    protected boolean supportsBatch() { return true; }

    @Override
    protected String inputHintKey() { return "hint.NUP"; }

    @Override
    protected VBox buildOptionsArea() {
        layoutBox = new ComboBox<>();
        layoutBox.getItems().addAll(NupLayout.values());
        layoutBox.setValue(NupLayout.TWO_UP);
        layoutBox.setConverter(new StringConverter<>() {
            @Override public String toString(NupLayout l) {
                return l == null ? "" : I18n.t("nup.layout." + l.id());
            }
            @Override public NupLayout fromString(String s) { return null; }
        });
        HBox layoutRow = new HBox(Ui.INLINE_GAP, fieldLabel("nup.layout.label"), layoutBox);

        bookletCheck = new CheckBox();
        I18n.bindText(bookletCheck::setText, "nup.booklet");
        // Booklet imposes its own 2-up saddle-stitch order, so the grid preset is moot.
        layoutBox.disableProperty().bind(bookletCheck.selectedProperty());

        return new VBox(Ui.OPTION_GAP, layoutRow, bookletCheck);
    }

    @Override
    protected void onRun() {
        List<Path> files = List.copyOf(fileList.getFiles());
        if (files.isEmpty()) return;
        NupLayout layout = layoutBox.getValue();
        boolean booklet = bookletCheck.isSelected();

        if (isBatchMode()) {
            runPerFile("verb.nup", (in, out) ->
                PdfNupImposer.execute(new NupOptions(in, layout, booklet, out)));
            return;
        }

        Path input = files.get(0);
        Path output = confirmOutputFor(input).orElse(null);
        if (output == null) return;
        Task<PdfResult> task = new Task<>() {
            @Override
            protected PdfResult call() throws Exception {
                updateMessage(I18n.t("msg.busy", I18n.t("verb.nup")));
                return OperationRunner.run(input, output,
                    (pdf, out) -> PdfNupImposer.execute(new NupOptions(pdf, layout, booklet, out)));
            }
        };
        progressPanel.run(task, output);
    }
}
