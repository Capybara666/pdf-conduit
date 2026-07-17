package com.pdfconduit.app.gui.wizard;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import com.pdfconduit.app.gui.component.DropZone;
import com.pdfconduit.app.i18n.I18n;
import com.pdfconduit.app.gui.component.FileListView;
import com.pdfconduit.core.model.PageRange;
import com.pdfconduit.core.model.PageSize;
import com.pdfconduit.core.model.PageSource;
import com.pdfconduit.core.util.FileTypeDetector;

import java.nio.file.Path;

public class Step1SelectFiles implements WizardStep {

    private final WizardModel model;
    private final FileListView fileList = new FileListView();
    private Node content;

    public Step1SelectFiles(WizardModel model) {
        this.model = model;
        fileList.getFiles().addListener((javafx.collections.ListChangeListener<Path>) c ->
            model.pages.setAll(fileList.getFiles().stream()
                .map(p -> FileTypeDetector.isPdf(p)
                    ? (PageSource) new PageSource.PdfPageSource(p, PageRange.ALL)
                    : new PageSource.ImageSource(p, PageSize.FIT))
                .toList()));
    }

    @Override
    public Node getContent() {
        if (content == null) content = build();
        return content;
    }

    private Node build() {
        Label title = new Label();
        I18n.bindText(title::setText, "wizard.step1.title");
        title.getStyleClass().add("panel-title");
        DropZone dropZone = new DropZone(fileList::addFiles);

        VBox top = new VBox(12, title, dropZone);
        BorderPane root = new BorderPane();
        root.getStyleClass().add("wizard-step");
        root.setTop(top);
        root.setCenter(fileList);
        BorderPane.setMargin(fileList, new Insets(12, 0, 0, 0));
        return root;
    }
}
