package org.example.app.gui.panels;

import javafx.concurrent.Task;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;
import org.example.app.i18n.I18n;
import org.example.core.model.PdfResult;
import org.example.core.model.UnlockOptions;
import org.example.core.operations.PdfUnlocker;
import org.example.core.service.OperationType;

import java.nio.file.Path;
import java.util.List;

/** Remove a password from a protected PDF. */
public class UnlockPanel extends BasePanel {

    private PasswordField passwordField;

    public UnlockPanel() { super("panel.UNLOCK.title", "run.UNLOCK", OperationType.UNLOCK); }

    @Override
    protected String inputHintKey() { return "hint.UNLOCK"; }

    @Override
    protected VBox buildOptionsArea() {
        Label passwordLabel = new Label();
        I18n.bindText(passwordLabel::setText, "password.field.label");
        passwordLabel.setStyle("-fx-font-size: 11px;");
        passwordField = new PasswordField();
        I18n.bindText(passwordField::setPromptText, "password.field.prompt");
        return new VBox(6, passwordLabel, passwordField);
    }

    @Override
    protected void onRun() {
        List<Path> files = List.copyOf(fileList.getFiles());
        if (files.isEmpty()) return;
        String password = passwordField.getText();

        Path input = files.get(0);
        Path output = resolveOutput(input.resolveSibling(
            stripExt(input.getFileName().toString()) + "_unlocked.pdf"));
        Task<PdfResult> task = new Task<>() {
            @Override
            protected PdfResult call() throws Exception {
                updateMessage(I18n.t("msg.unlocking"));
                return PdfUnlocker.execute(new UnlockOptions(input, password, output));
            }
        };
        progressPanel.run(task, output);
    }
}
