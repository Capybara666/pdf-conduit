package com.pdfconduit.app.gui.panels;

import javafx.collections.ListChangeListener;
import javafx.concurrent.Task;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import com.pdfconduit.app.gui.Ui;
import com.pdfconduit.app.i18n.I18n;
import com.pdfconduit.core.convert.DocumentConverter;
import com.pdfconduit.core.model.MetadataOptions;
import com.pdfconduit.core.model.PdfMetadata;
import com.pdfconduit.core.model.PdfResult;
import com.pdfconduit.core.operations.PdfMetadataEditor;
import com.pdfconduit.core.service.OperationRunner;
import com.pdfconduit.core.service.OperationType;

import java.nio.file.Path;
import java.util.List;

/** View and edit (or strip) a PDF's title/author/subject/keywords. */
public class MetadataPanel extends BasePanel {

    private TextField titleField;
    private TextField authorField;
    private TextField subjectField;
    private TextField keywordsField;
    private CheckBox stripBox;

    public MetadataPanel() { super("panel.METADATA.title", "run.METADATA", OperationType.METADATA); }

    @Override
    protected boolean supportsBatch() { return true; }

    @Override
    protected String inputHintKey() { return "hint.METADATA"; }

    @Override
    protected VBox buildOptionsArea() {
        titleField = new TextField();
        authorField = new TextField();
        subjectField = new TextField();
        keywordsField = new TextField();

        stripBox = new CheckBox();
        I18n.bindText(stripBox::setText, "metadata.strip");
        // When stripping, the field values are irrelevant — grey them out.
        for (TextField f : List.of(titleField, authorField, subjectField, keywordsField)) {
            f.disableProperty().bind(stripBox.selectedProperty());
        }

        // Prefill the fields from the first PDF dropped in.
        fileList.getFiles().addListener((ListChangeListener<Path>) c -> prefill());

        return new VBox(Ui.OPTION_GAP,
            labeledField("metadata.title.label", titleField),
            labeledField("metadata.author.label", authorField),
            labeledField("metadata.subject.label", subjectField),
            labeledField("metadata.keywords.label", keywordsField),
            stripBox);
    }

    /** Loads the first input's metadata into the fields (PDF inputs only). */
    private void prefill() {
        if (fileList.getFiles().isEmpty()) return;
        Path f = fileList.getFiles().get(0);
        if (!DocumentConverter.isPdf(f)) return;
        try {
            PdfMetadata md = PdfMetadataEditor.read(f);
            titleField.setText(nz(md.title()));
            authorField.setText(nz(md.author()));
            subjectField.setText(nz(md.subject()));
            keywordsField.setText(nz(md.keywords()));
        } catch (Exception ignored) {
            // e.g. a password-protected PDF — leave the fields blank.
        }
    }

    @Override
    protected void onRun() {
        List<Path> files = List.copyOf(fileList.getFiles());
        if (files.isEmpty()) return;
        boolean strip = stripBox.isSelected();
        // The entered fields (or Strip) apply to every file in batch mode.
        String title = strip ? null : titleField.getText();
        String author = strip ? null : authorField.getText();
        String subject = strip ? null : subjectField.getText();
        String keywords = strip ? null : keywordsField.getText();

        if (isBatchMode()) {
            runPerFile("verb.metadata", (in, out) -> PdfMetadataEditor.execute(
                new MetadataOptions(in, title, author, subject, keywords, strip, out)));
            return;
        }

        Path input = files.get(0);
        Path output = resolveOutputFor(input);
        Task<PdfResult> task = new Task<>() {
            @Override
            protected PdfResult call() throws Exception {
                updateMessage(I18n.t("msg.busy", I18n.t("verb.metadata")));
                return OperationRunner.run(input, output, (pdf, out) -> PdfMetadataEditor.execute(
                    new MetadataOptions(pdf, title, author, subject, keywords, strip, out)));
            }
        };
        progressPanel.run(task, output);
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
