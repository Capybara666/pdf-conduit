package org.example.app.gui.panels;

import javafx.collections.ListChangeListener;
import javafx.concurrent.Task;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import org.example.app.i18n.I18n;
import org.example.core.convert.DocumentConverter;
import org.example.core.model.MetadataOptions;
import org.example.core.model.PageSize;
import org.example.core.model.PdfMetadata;
import org.example.core.model.PdfResult;
import org.example.core.operations.PdfMetadataEditor;
import org.example.core.service.OperationType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

        VBox box = new VBox(6,
            labelFor("metadata.title.label", titleField),
            labelFor("metadata.author.label", authorField),
            labelFor("metadata.subject.label", subjectField),
            labelFor("metadata.keywords.label", keywordsField),
            stripBox);
        return box;
    }

    private VBox labelFor(String labelKey, TextField f) {
        Label l = new Label();
        I18n.bindText(l::setText, labelKey);
        l.setStyle("-fx-font-size: 11px;");
        return new VBox(2, l, f);
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
        String title = titleField.getText();
        String author = authorField.getText();
        String subject = subjectField.getText();
        String keywords = keywordsField.getText();

        Path input = files.get(0);
        Path output = resolveOutput(input.resolveSibling(
            stripExt(input.getFileName().toString()) + "_metadata.pdf"));
        Task<PdfResult> task = new Task<>() {
            @Override
            protected PdfResult call() throws Exception {
                updateMessage(I18n.t("msg.metadata"));
                List<Path> temps = new ArrayList<>();
                try {
                    Path pdf = DocumentConverter.ensurePdf(input, PageSize.FIT, temps);
                    return PdfMetadataEditor.execute(new MetadataOptions(pdf,
                        strip ? null : title, strip ? null : author,
                        strip ? null : subject, strip ? null : keywords, strip, output));
                } finally {
                    for (Path t : temps) Files.deleteIfExists(t);
                }
            }
        };
        progressPanel.run(task, output);
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
