package org.example.app.gui.panels;

import javafx.concurrent.Task;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;
import org.example.app.i18n.I18n;
import org.example.core.convert.DocumentConverter;
import org.example.core.model.PageSize;
import org.example.core.model.PdfResult;
import org.example.core.model.ProtectOptions;
import org.example.core.operations.PdfProtector;
import org.example.core.service.OperationType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Add a password (AES-128) to a PDF. */
public class ProtectPanel extends BasePanel {

    private PasswordField passwordField;
    private PasswordField ownerField;

    public ProtectPanel() { super("panel.PROTECT.title", "run.PROTECT", OperationType.PROTECT); }

    @Override
    protected String inputHintKey() { return "hint.PROTECT"; }

    @Override
    protected VBox buildOptionsArea() {
        Label passwordLabel = new Label();
        I18n.bindText(passwordLabel::setText, "password.field.label");
        passwordLabel.setStyle("-fx-font-size: 11px;");
        passwordField = new PasswordField();
        I18n.bindText(passwordField::setPromptText, "password.field.prompt");

        Label ownerLabel = new Label();
        I18n.bindText(ownerLabel::setText, "password.owner.label");
        ownerLabel.setStyle("-fx-font-size: 11px;");
        ownerField = new PasswordField();
        I18n.bindText(ownerField::setPromptText, "password.owner.prompt");

        return new VBox(6, passwordLabel, passwordField, ownerLabel, ownerField);
    }

    @Override
    protected void onRun() {
        List<Path> files = List.copyOf(fileList.getFiles());
        if (files.isEmpty()) return;
        String password = passwordField.getText();
        String owner = ownerField.getText();

        Path input = files.get(0);
        Path output = resolveOutput(input.resolveSibling(
            stripExt(input.getFileName().toString()) + "_protected.pdf"));
        Task<PdfResult> task = new Task<>() {
            @Override
            protected PdfResult call() throws Exception {
                updateMessage(I18n.t("msg.protecting"));
                List<Path> temps = new ArrayList<>();
                try {
                    Path pdf = DocumentConverter.ensurePdf(input, PageSize.FIT, temps);
                    return PdfProtector.execute(new ProtectOptions(
                        pdf, password, owner == null ? "" : owner, output));
                } finally {
                    for (Path t : temps) Files.deleteIfExists(t);
                }
            }
        };
        progressPanel.run(task, output);
    }
}
