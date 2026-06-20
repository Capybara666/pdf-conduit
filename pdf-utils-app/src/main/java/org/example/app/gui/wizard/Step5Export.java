package org.example.app.gui.wizard;

import javafx.concurrent.Task;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.example.app.gui.component.ProgressPanel;
import org.example.app.i18n.I18n;
import org.example.core.convert.DocumentConverter;
import org.example.core.model.*;
import org.example.core.operations.PdfCompressor;
import org.example.core.operations.PdfMerger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class Step5Export implements WizardStep {

    private final WizardModel model;
    private TextField outputField;
    private ProgressPanel progressPanel;
    private Node content;

    public Step5Export(WizardModel model) { this.model = model; }

    @Override
    public Node getContent() {
        if (content == null) content = build();
        // Auto-fill output path from first page source when field is empty
        autofillOutput();
        return content;
    }

    private void autofillOutput() {
        if (model.outputPath.get().isBlank() && !model.pages.isEmpty()) {
            PageSource first = model.pages.get(0);
            Path base = switch (first) {
                case PageSource.PdfPageSource ps -> ps.file();
                case PageSource.ImageSource is   -> is.file();
            };
            String stem = base.getFileName().toString().replaceAll("\\.[^.]+$", "");
            model.outputPath.set(
                org.example.app.gui.util.OutputPaths.defaultDir().resolve(stem + "_merged.pdf").toString());
        }
    }

    private Node build() {
        Label title = new Label();
        I18n.bindText(title::setText, "wizard.step5.title");
        title.getStyleClass().add("panel-title");

        outputField = new TextField();
        I18n.bindText(outputField::setPromptText, "output.file.prompt");
        outputField.textProperty().bindBidirectional(model.outputPath);
        HBox.setHgrow(outputField, Priority.ALWAYS);

        Button browseBtn = new Button("…");
        browseBtn.getStyleClass().add("btn-secondary");
        browseBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(I18n.t("chooser.saveas"));
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(I18n.t("filter.pdf"), "*.pdf"));
            Stage stage = (Stage) browseBtn.getScene().getWindow();
            var file = chooser.showSaveDialog(stage);
            if (file != null) outputField.setText(file.getAbsolutePath());
        });
        HBox outputRow = new HBox(6, outputField, browseBtn);

        progressPanel = new ProgressPanel("wizard.step5.run");
        progressPanel.getRunButton().setOnAction(e -> onFinish());

        Label outputLabel = new Label();
        I18n.bindText(outputLabel::setText, "wizard.step5.outputlabel");
        VBox box = new VBox(14, title, outputLabel, outputRow, progressPanel);
        box.setStyle("-fx-padding: 18;");
        return box;
    }

    @Override
    public void onFinish() {
        String pathStr = model.outputPath.get();
        if (pathStr == null || pathStr.isBlank()) return;
        Path output = Path.of(pathStr.endsWith(".pdf") ? pathStr : pathStr + ".pdf");
        PageSize pageSize = model.globalPageSize.get();
        List<PageSource> selected = List.copyOf(model.pages);
        boolean compress = model.compress.get();
        long targetBytes = model.targetSizeBytes.get();

        Task<ExportOutcome> task = new Task<>() {
            @Override
            protected ExportOutcome call() throws Exception {
                updateMessage(I18n.t("msg.merging.pages"));
                List<Path> temps = new ArrayList<>();
                try {
                    List<PageSource> pages = new ArrayList<>();
                    for (PageSource ps : selected) {
                        if (ps instanceof PageSource.ImageSource is) {
                            // A real image keeps the page size chosen in Step 3; a
                            // document is converted to PDF first.
                            if (DocumentConverter.classify(is.file()) == DocumentConverter.Kind.OFFICE) {
                                Path pdf = DocumentConverter.ensurePdf(is.file(), pageSize, temps);
                                pages.add(new PageSource.PdfPageSource(pdf, PageRange.ALL));
                            } else {
                                pages.add(new PageSource.ImageSource(is.file(), pageSize));
                            }
                        } else {
                            pages.add(ps);
                        }
                    }
                    Path merged = compress ? output.resolveSibling("_wizard_tmp.pdf") : output;
                    PdfMerger.execute(new MergeOptions(pages, merged));
                    if (compress) {
                        updateMessage(I18n.t("msg.compressing"));
                        CompressResult r = PdfCompressor.execute(
                            new CompressOptions(merged, targetBytes, output));
                        merged.toFile().delete();
                        return new ExportOutcome(true, r.targetReached(), r.resultBytes());
                    }
                    return new ExportOutcome(false, true, 0);
                } finally {
                    for (Path t : temps) Files.deleteIfExists(t);
                }
            }
        };
        progressPanel.run(task, output, o -> (o.compressed() && !o.targetReached())
            ? I18n.t("compress.warn.simple", ProgressPanel.humanSize(o.resultBytes()))
            : null);
    }

    private record ExportOutcome(boolean compressed, boolean targetReached, long resultBytes) {}
}
