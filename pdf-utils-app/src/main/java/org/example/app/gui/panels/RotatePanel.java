package org.example.app.gui.panels;

import javafx.beans.binding.Bindings;
import javafx.concurrent.Task;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.example.app.gui.component.PageSelectDialog;
import org.example.core.convert.DocumentConverter;
import org.example.core.model.PageSize;
import org.example.core.model.RotateOptions;
import org.example.core.model.RotateResult;
import org.example.core.operations.PdfRotator;
import org.example.core.service.OperationType;
import org.example.app.i18n.I18n;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
        Label pagesLabel = new Label();
        I18n.bindText(pagesLabel::setText, "rotate.pages.label");
        pagesLabel.getStyleClass().add("text-sm");
        pagesField = new TextField();
        I18n.bindText(pagesField::setPromptText, "rotate.pages.prompt");
        HBox.setHgrow(pagesField, Priority.ALWAYS);
        Button pick = new Button();
        I18n.bindText(pick::setText, "select.pick");
        pick.getStyleClass().add("btn-secondary");
        pick.disableProperty().bind(Bindings.isEmpty(fileList.getFiles()));
        pick.setOnAction(e -> pickPages());

        Label angleLabel = new Label();
        I18n.bindText(angleLabel::setText, "rotate.angle.label");
        angleLabel.getStyleClass().add("text-sm");
        angleBox = new ComboBox<>();
        angleBox.getItems().addAll(90, 180, 270);
        angleBox.setValue(90);

        HBox angleRow = new HBox(8, angleLabel, angleBox);
        return new VBox(6, pagesLabel, new HBox(6, pagesField, pick), angleRow);
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
            runPerFile("Rotating", (in, out) ->
                PdfRotator.execute(new RotateOptions(in, SplitPanel.resolveRange(pagesExpr, in), angle, out)));
            return;
        }

        Path input = files.get(0);
        Path output = resolveOutput(input.resolveSibling(
            stripExt(input.getFileName().toString()) + "_rotated.pdf"));
        Task<RotateResult> task = new Task<>() {
            @Override
            protected RotateResult call() throws Exception {
                updateMessage(I18n.t("msg.rotating"));
                List<Path> temps = new ArrayList<>();
                try {
                    Path pdf = DocumentConverter.ensurePdf(input, PageSize.FIT, temps);
                    return PdfRotator.execute(
                        new RotateOptions(pdf, SplitPanel.resolveRange(pagesExpr, pdf), angle, output));
                } finally {
                    for (Path t : temps) Files.deleteIfExists(t);
                }
            }
        };
        progressPanel.run(task, output);
    }
}
