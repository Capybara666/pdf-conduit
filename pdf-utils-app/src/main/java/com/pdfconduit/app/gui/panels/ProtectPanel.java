package com.pdfconduit.app.gui.panels;

import javafx.concurrent.Task;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;
import com.pdfconduit.app.gui.Ui;
import com.pdfconduit.app.i18n.I18n;
import com.pdfconduit.core.model.PdfResult;
import com.pdfconduit.core.model.ProtectOptions;
import com.pdfconduit.core.operations.PdfProtector;
import com.pdfconduit.core.service.OperationRunner;
import com.pdfconduit.core.service.OperationType;

import java.nio.file.Path;
import java.util.List;

/** Add a password (AES-128) to a PDF. */
public class ProtectPanel extends BasePanel {

    private PasswordField passwordField;
    private PasswordField ownerField;

    public ProtectPanel() { super("panel.PROTECT.title", "run.PROTECT", OperationType.PROTECT); }

    @Override
    protected boolean supportsBatch() { return true; }

    @Override
    protected String inputHintKey() { return "hint.PROTECT"; }

    @Override
    protected VBox buildOptionsArea() {
        passwordField = new PasswordField();
        I18n.bindText(passwordField::setPromptText, "password.field.prompt");
        ownerField = new PasswordField();
        I18n.bindText(ownerField::setPromptText, "password.owner.prompt");
        return new VBox(Ui.OPTION_GAP,
            labeledField("password.field.label", passwordField),
            labeledField("password.owner.label", ownerField));
    }

    @Override
    protected void onRun() {
        List<Path> files = List.copyOf(fileList.getFiles());
        if (files.isEmpty()) return;
        String password = passwordField.getText();
        String owner = ownerField.getText();
        String ownerPw = owner == null ? "" : owner;

        if (isBatchMode()) {
            runPerFile("verb.protect", (in, out) ->
                PdfProtector.execute(new ProtectOptions(in, password, ownerPw, out)));
            return;
        }

        Path input = files.get(0);
        Path output = resolveOutputFor(input);
        Task<PdfResult> task = new Task<>() {
            @Override
            protected PdfResult call() throws Exception {
                updateMessage(I18n.t("msg.busy", I18n.t("verb.protect")));
                return OperationRunner.run(input, output,
                    (pdf, out) -> PdfProtector.execute(new ProtectOptions(pdf, password, ownerPw, out)));
            }
        };
        progressPanel.run(task, output);
    }
}
