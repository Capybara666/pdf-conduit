package org.example.app.gui.panels;

import javafx.concurrent.Task;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.example.core.model.PageRange;
import org.example.core.model.RotateOptions;
import org.example.core.model.RotateResult;
import org.example.core.operations.PdfRotator;
import org.example.core.util.PageRangeParser;

import java.nio.file.Path;
import java.util.List;

public class RotatePanel extends BasePanel {

    private TextField pagesField;
    private ComboBox<Integer> angleBox;

    public RotatePanel() { super("Rotate Pages", "▶  Rotate"); }

    @Override
    protected VBox buildOptionsArea() {
        Label pagesLabel = new Label("Pages (blank = all):");
        pagesLabel.setStyle("-fx-font-size: 11px;");
        pagesField = new TextField();
        pagesField.setPromptText("1,3,5-8");

        Label angleLabel = new Label("Angle:");
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
        Path input = files.get(0);
        String pagesExpr = pagesField.getText();
        int angle = angleBox.getValue();

        Path output = resolveOutput(input.resolveSibling(
            stripExt(input.getFileName().toString()) + "_rotated.pdf"));

        Task<RotateResult> task = new Task<>() {
            @Override
            protected RotateResult call() throws Exception {
                updateMessage("Rotating…");
                PageRange range;
                if (pagesExpr == null || pagesExpr.isBlank()) {
                    range = PageRange.ALL;
                } else {
                    int total;
                    try (var doc = org.apache.pdfbox.Loader.loadPDF(input.toFile())) {
                        total = doc.getNumberOfPages();
                    }
                    range = PageRangeParser.parse(pagesExpr, total);
                }
                return PdfRotator.execute(new RotateOptions(input, range, angle, output));
            }
        };
        progressPanel.run(task, output);
    }

    private static String stripExt(String n) {
        int d = n.lastIndexOf('.');
        return d >= 0 ? n.substring(0, d) : n;
    }
}
