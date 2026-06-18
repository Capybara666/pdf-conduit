package org.example.app.gui.panels;

import javafx.concurrent.Task;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.example.core.convert.DocumentConverter;
import org.example.core.model.PageSize;
import org.example.core.model.RotateOptions;
import org.example.core.model.RotateResult;
import org.example.core.operations.PdfRotator;
import org.example.app.i18n.I18n;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class RotatePanel extends BasePanel {

    private TextField pagesField;
    private ComboBox<Integer> angleBox;

    public RotatePanel() { super(I18n.t("panel.ROTATE.title"), I18n.t("run.ROTATE"), "_rotated"); }

    @Override
    protected boolean supportsBatch() { return true; }

    @Override
    protected String inputHint() {
        return I18n.t("hint.ROTATE");
    }

    @Override
    protected VBox buildOptionsArea() {
        Label pagesLabel = new Label(I18n.t("rotate.pages.label"));
        pagesLabel.setStyle("-fx-font-size: 11px;");
        pagesField = new TextField();
        pagesField.setPromptText(I18n.t("rotate.pages.prompt"));

        Label angleLabel = new Label(I18n.t("rotate.angle.label"));
        angleLabel.setStyle("-fx-font-size: 11px;");
        angleBox = new ComboBox<>();
        angleBox.getItems().addAll(90, 180, 270);
        angleBox.setValue(90);

        HBox angleRow = new HBox(8, angleLabel, angleBox);
        return new VBox(6, pagesLabel, pagesField, angleRow);
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
