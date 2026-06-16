package org.example.app.gui.wizard;

import javafx.concurrent.Task;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.example.app.gui.component.ProgressPanel;
import org.example.core.model.*;
import org.example.core.operations.PdfCompressor;
import org.example.core.operations.PdfMerger;

import java.nio.file.Path;
import java.util.List;

public class Step5Export implements WizardStep {

    private final WizardModel model;
    private TextField outputField;
    private ProgressPanel progressPanel;

    public Step5Export(WizardModel model) { this.model = model; }

    @Override
    public Node getContent() {
        Label title = new Label("Step 5: Export");
        title.getStyleClass().add("panel-title");

        outputField = new TextField();
        outputField.setPromptText("Output file path…");
        outputField.textProperty().bindBidirectional(model.outputPath);
        HBox.setHgrow(outputField, Priority.ALWAYS);

        Button browseBtn = new Button("📁");
        browseBtn.getStyleClass().add("btn-secondary");
        browseBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Save as");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF", "*.pdf"));
            Stage stage = (Stage) browseBtn.getScene().getWindow();
            var file = chooser.showSaveDialog(stage);
            if (file != null) outputField.setText(file.getAbsolutePath());
        });
        HBox outputRow = new HBox(6, outputField, browseBtn);

        progressPanel = new ProgressPanel("▶  Generate PDF");
        progressPanel.getRunButton().setOnAction(e -> onFinish());

        VBox box = new VBox(14, title, new Label("Output file:"), outputRow, progressPanel);
        box.setStyle("-fx-padding: 18;");
        return box;
    }

    @Override
    public void onFinish() {
        String pathStr = model.outputPath.get();
        if (pathStr == null || pathStr.isBlank()) return;
        Path output = Path.of(pathStr.endsWith(".pdf") ? pathStr : pathStr + ".pdf");
        List<PageSource> pages = List.copyOf(model.pages);
        boolean compress = model.compress.get();
        long targetBytes = model.targetSizeBytes.get();

        Task<Path> task = new Task<>() {
            @Override
            protected Path call() throws Exception {
                updateMessage("Merging pages…");
                Path merged = compress ? output.resolveSibling("_wizard_tmp.pdf") : output;
                PdfMerger.execute(new MergeOptions(pages, merged));
                if (compress) {
                    updateMessage("Compressing…");
                    PdfCompressor.execute(new CompressOptions(merged, targetBytes, output));
                    merged.toFile().delete();
                }
                return output;
            }
        };
        progressPanel.run(task, output);
    }
}
