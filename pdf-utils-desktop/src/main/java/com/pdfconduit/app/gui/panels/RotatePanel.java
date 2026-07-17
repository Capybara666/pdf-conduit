package com.pdfconduit.app.gui.panels;

import javafx.beans.binding.Bindings;
import javafx.concurrent.Task;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import com.pdfconduit.app.gui.Ui;
import com.pdfconduit.app.gui.component.PageSelectDialog;
import com.pdfconduit.core.model.RotateOptions;
import com.pdfconduit.core.model.RotateResult;
import com.pdfconduit.core.operations.PdfRotator;
import com.pdfconduit.core.service.OperationRunner;
import com.pdfconduit.core.service.OperationType;
import com.pdfconduit.app.i18n.I18n;

import java.nio.file.Path;
import java.util.List;

public class RotatePanel extends BasePanel {

    private TextField pagesField;
    private ComboBox<Integer> angleBox;

    public RotatePanel() { super("panel.ROTATE.title", "run.ROTATE", OperationType.ROTATE); }

    @Override
    protected boolean supportsBatch() { return true; }

    @Override
    protected String inputHintKey() {
        return "hint.ROTATE";
    }

    @Override
    protected VBox buildOptionsArea() {
        pagesField = new TextField();
        I18n.bindText(pagesField::setPromptText, "rotate.pages.prompt");
        HBox.setHgrow(pagesField, Priority.ALWAYS);
        Button pick = new Button();
        I18n.bindText(pick::setText, "select.pick");
        pick.getStyleClass().add("btn-secondary");
        pick.disableProperty().bind(Bindings.isEmpty(fileList.getFiles()));
        pick.setOnAction(e -> pickPages());
        VBox pagesGroup = labeledField("rotate.pages.label", new HBox(Ui.INLINE_GAP, pagesField, pick));

        angleBox = new ComboBox<>();
        angleBox.getItems().addAll(90, 180, 270);
        angleBox.setValue(90);
        HBox angleRow = new HBox(Ui.INLINE_GAP, fieldLabel("rotate.angle.label"), angleBox);

        return new VBox(Ui.OPTION_GAP, pagesGroup, angleRow);
    }

    /** Opens the visual page picker for the first file and writes the result back. */
    private void pickPages() {
        if (fileList.getFiles().isEmpty()) return;
        PageSelectDialog.choose(getScene().getWindow(), fileList.getFiles().get(0),
            pagesField.getText()).ifPresent(pagesField::setText);
    }

    @Override
    protected void onRun() {
        List<Path> files = List.copyOf(fileList.getFiles());
        if (files.isEmpty()) return;
        String pagesExpr = pagesField.getText();
        int angle = angleBox.getValue();

        if (isBatchMode()) {
            runPerFile("verb.rotate", (in, out) ->
                PdfRotator.execute(new RotateOptions(in, SplitPanel.resolveRange(pagesExpr, in), angle, out)));
            return;
        }

        Path input = files.get(0);
        Path output = confirmOutputFor(input).orElse(null);
        if (output == null) return;
        Task<RotateResult> task = new Task<>() {
            @Override
            protected RotateResult call() throws Exception {
                updateMessage(I18n.t("msg.busy", I18n.t("verb.rotate")));
                return OperationRunner.run(input, output,
                    (pdf, out) -> PdfRotator.execute(
                        new RotateOptions(pdf, SplitPanel.resolveRange(pagesExpr, pdf), angle, out)));
            }
        };
        progressPanel.run(task, output);
    }
}
