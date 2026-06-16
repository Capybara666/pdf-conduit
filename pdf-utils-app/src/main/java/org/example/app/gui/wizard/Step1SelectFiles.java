package org.example.app.gui.wizard;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.example.app.gui.component.DropZone;
import org.example.app.gui.component.FileListView;
import org.example.core.model.PageRange;
import org.example.core.model.PageSize;
import org.example.core.model.PageSource;
import org.example.core.util.FileTypeDetector;

import java.nio.file.Path;

public class Step1SelectFiles implements WizardStep {

    private final WizardModel model;
    private final FileListView fileList = new FileListView();

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
        Label title = new Label("Step 1: Select files");
        title.getStyleClass().add("panel-title");
        DropZone dropZone = new DropZone(fileList::addFiles);
        VBox box = new VBox(12, title, dropZone, fileList);
        box.setStyle("-fx-padding: 18;");
        return box;
    }
}
