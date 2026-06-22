package com.pdfconduit.app.gui.panels;

import javafx.beans.binding.Bindings;
import javafx.concurrent.Task;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import com.pdfconduit.app.gui.Ui;
import com.pdfconduit.app.gui.component.PageSelectDialog;
import com.pdfconduit.core.convert.DocumentConverter;
import com.pdfconduit.core.model.ImageFormat;
import com.pdfconduit.core.model.PageSize;
import com.pdfconduit.core.model.PdfToImageOptions;
import com.pdfconduit.core.operations.PdfToImageConverter;
import com.pdfconduit.core.service.OperationType;
import com.pdfconduit.app.i18n.I18n;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Exports the pages of one or more PDFs (or any convertible document) as PNG/JPEG
 * images into a folder. Output is always a folder — one image per page — so this
 * is a terminal operation: it has no pipeline node (the output is not a PDF).
 */
public class PdfToImagesPanel extends BasePanel {

    private ComboBox<ImageFormat> formatBox;
    private ComboBox<Integer> dpiBox;
    private Slider qualitySlider;
    private TextField pagesField;

    public PdfToImagesPanel() {
        super("panel.TO_IMAGES.title", "run.TO_IMAGES", OperationType.PDF_TO_IMAGES);
    }

    @Override
    protected boolean supportsBatch() { return true; }

    /** Images always go into a folder, even for a single input. */
    @Override
    protected boolean folderOnly() { return true; }

    @Override
    protected String inputHintKey() { return "hint.TO_IMAGES"; }

    @Override
    protected VBox buildOptionsArea() {
        formatBox = new ComboBox<>();
        formatBox.getItems().addAll(ImageFormat.PNG, ImageFormat.JPEG);
        formatBox.setValue(ImageFormat.PNG);
        HBox formatRow = new HBox(Ui.INLINE_GAP, fieldLabel("toimages.format.label"), formatBox);

        dpiBox = new ComboBox<>();
        dpiBox.getItems().addAll(72, 150, 300, 600);
        dpiBox.setValue(150);
        com.pdfconduit.app.gui.component.Forms.tip(dpiBox, "tooltip.dpi");
        HBox dpiRow = new HBox(Ui.INLINE_GAP, fieldLabel("toimages.dpi.label"), dpiBox);

        qualitySlider = new Slider(0.1, 1.0, 0.85);
        com.pdfconduit.app.gui.component.Forms.tip(qualitySlider, "tooltip.quality");
        qualitySlider.setPrefWidth(160);
        Label qualityValue = new Label();
        qualityValue.getStyleClass().add("text-caption");
        qualityValue.textProperty().bind(Bindings.format("%.0f%%", qualitySlider.valueProperty().multiply(100)));
        HBox qualityRow = new HBox(Ui.INLINE_GAP, fieldLabel("toimages.quality.label"), qualitySlider, qualityValue);
        // JPEG only — hide the quality row for PNG.
        qualityRow.visibleProperty().bind(formatBox.valueProperty().isEqualTo(ImageFormat.JPEG));
        qualityRow.managedProperty().bind(qualityRow.visibleProperty());

        pagesField = new TextField();
        I18n.bindText(pagesField::setPromptText, "toimages.pages.prompt");
        HBox.setHgrow(pagesField, Priority.ALWAYS);
        Button pick = new Button();
        I18n.bindText(pick::setText, "select.pick");
        pick.getStyleClass().add("btn-secondary");
        pick.disableProperty().bind(Bindings.isEmpty(fileList.getFiles()));
        pick.setOnAction(e -> pickPages());
        VBox pagesGroup = labeledField("toimages.pages.label", new HBox(Ui.INLINE_GAP, pagesField, pick));

        return new VBox(Ui.OPTION_GAP, formatRow, dpiRow, qualityRow, pagesGroup);
    }

    private void pickPages() {
        if (fileList.getFiles().isEmpty()) return;
        PageSelectDialog.choose(getScene().getWindow(), fileList.getFiles().get(0),
            pagesField.getText()).ifPresent(pagesField::setText);
    }

    @Override
    protected void onRun() {
        List<Path> files = List.copyOf(fileList.getFiles());
        if (files.isEmpty()) return;
        ImageFormat format = formatBox.getValue();
        int dpi = dpiBox.getValue();
        float quality = (float) qualitySlider.getValue();
        String pagesExpr = pagesField.getText();
        Path dir = outputDir();

        Task<Path> task = new Task<>() {
            @Override
            protected Path call() throws Exception {
                Files.createDirectories(dir);
                for (int i = 0; i < files.size(); i++) {
                    if (isCancelled()) break;
                    Path in = files.get(i);
                    updateMessage(I18n.t("msg.busy.count", I18n.t("verb.toimages"), i + 1, files.size()));
                    List<Path> temps = new ArrayList<>();
                    try {
                        Path pdf = DocumentConverter.ensurePdf(in, PageSize.FIT, temps);
                        PdfToImageConverter.execute(new PdfToImageOptions(
                            pdf, format, dpi, SplitPanel.resolveRange(pagesExpr, pdf), quality,
                            dir, stripExt(in.getFileName().toString())));
                    } catch (Exception ex) {
                        throw new Exception(in.getFileName() + ": " + ex.getMessage(), ex);
                    } finally {
                        for (Path t : temps) Files.deleteIfExists(t);
                    }
                    updateProgress(i + 1, files.size());
                }
                return dir;
            }
        };
        progressPanel.run(task, dir);
    }
}
