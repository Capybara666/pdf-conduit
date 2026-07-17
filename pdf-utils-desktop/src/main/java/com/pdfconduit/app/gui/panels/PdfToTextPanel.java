package com.pdfconduit.app.gui.panels;

import javafx.beans.binding.Bindings;
import javafx.concurrent.Task;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import com.pdfconduit.app.gui.Ui;
import com.pdfconduit.app.gui.component.PageSelectDialog;
import com.pdfconduit.core.convert.DocumentConverter;
import com.pdfconduit.core.model.PdfToTextOptions;
import com.pdfconduit.core.model.TextFormat;
import com.pdfconduit.core.operations.PdfTextExporter;
import com.pdfconduit.core.service.OperationRunner;
import com.pdfconduit.core.service.OperationRunner.BatchOutcome;
import com.pdfconduit.core.service.OperationType;
import com.pdfconduit.app.i18n.I18n;

import java.nio.file.Path;
import java.util.List;

/**
 * Exports the text of one or more PDFs (or any convertible document) as TXT or
 * Word (.docx) into a folder. Word requires LibreOffice — the option is disabled
 * with a hint when it is unavailable. A terminal operation (output is not a PDF).
 */
public class PdfToTextPanel extends BasePanel {

    private ToggleGroup formatGroup;
    private RadioButton txtRadio;
    private RadioButton wordRadio;
    private TextField pagesField;

    public PdfToTextPanel() {
        super("panel.TO_TEXT.title", "run.TO_TEXT", OperationType.PDF_TO_TEXT);
    }

    @Override
    protected boolean supportsBatch() { return true; }

    /** Output always goes to a folder (one file per input). */
    @Override
    protected boolean folderOnly() { return true; }

    @Override
    protected String inputHintKey() { return "hint.TO_TEXT"; }

    @Override
    protected VBox buildOptionsArea() {
        formatGroup = new ToggleGroup();
        txtRadio = new RadioButton();
        I18n.bindText(txtRadio::setText, "totext.format.txt");
        txtRadio.setToggleGroup(formatGroup);
        txtRadio.setSelected(true);
        wordRadio = new RadioButton();
        I18n.bindText(wordRadio::setText, "totext.format.docx");
        wordRadio.setToggleGroup(formatGroup);
        formatGroup.selectedToggleProperty().addListener((o, a, b) -> { if (b == null) a.setSelected(true); });

        HBox formatRow = new HBox(Ui.INLINE_GAP, fieldLabel("totext.format.label"), txtRadio, wordRadio);
        formatRow.getStyleClass().add("row-left");

        VBox formatGroupBox = new VBox(Ui.LABEL_FIELD_GAP, formatRow);
        // Gate Word on LibreOffice: disable the option and explain why when it's missing.
        if (!DocumentConverter.officeConversionAvailable()) {
            wordRadio.setDisable(true);
            Label needsLo = new Label();
            I18n.bindText(needsLo::setText, "totext.word.needslo");
            needsLo.getStyleClass().add("text-caption");
            needsLo.setWrapText(true);
            formatGroupBox.getChildren().add(needsLo);
        }

        pagesField = new TextField();
        I18n.bindText(pagesField::setPromptText, "split.pages.prompt");
        HBox.setHgrow(pagesField, Priority.ALWAYS);
        Button pick = new Button();
        I18n.bindText(pick::setText, "select.pick");
        pick.getStyleClass().add("btn-secondary");
        pick.disableProperty().bind(Bindings.isEmpty(fileList.getFiles()));
        pick.setOnAction(e -> pickPages());
        VBox pagesGroup = labeledField("split.pages.label", new HBox(Ui.INLINE_GAP, pagesField, pick));

        return new VBox(Ui.OPTION_GAP, formatGroupBox, pagesGroup);
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
        TextFormat format = wordRadio.isSelected() ? TextFormat.DOCX : TextFormat.TXT;
        String pagesExpr = pagesField.getText();
        Path dir = outputDir();

        Task<BatchOutcome> task = new Task<>() {
            @Override
            protected BatchOutcome call() throws Exception {
                updateMessage(I18n.t("msg.busy", I18n.t("verb.totext")));
                return OperationRunner.runBatchMulti(files, dir,
                    (pdf, in) -> PdfTextExporter.execute(new PdfToTextOptions(pdf, format,
                        SplitPanel.resolveRange(pagesExpr, pdf), dir,
                        stripExt(in.getFileName().toString()))),
                    (completed, total) -> {
                        updateMessage(I18n.t("msg.busy.count", I18n.t("verb.totext"), completed, total));
                        updateProgress(completed, total);
                    },
                    this::isCancelled);
            }
        };
        progressPanel.run(task, dir, BasePanel::batchWarning);
    }
}
