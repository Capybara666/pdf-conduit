package org.example.app.gui.wizard;

import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.example.core.model.PageSize;

public class Step3PageSettings implements WizardStep {

    private final WizardModel model;

    public Step3PageSettings(WizardModel model) { this.model = model; }

    @Override
    public Node getContent() {
        Label title = new Label("Step 3: Page settings");
        title.getStyleClass().add("panel-title");

        Label sizeLabel = new Label("Page size for images / mixed pages:");
        sizeLabel.setStyle("-fx-font-size: 11px;");

        ComboBox<PageSize> sizeBox = new ComboBox<>();
        sizeBox.getItems().addAll(PageSize.values());
        sizeBox.valueProperty().bindBidirectional(model.globalPageSize);

        HBox row = new HBox(8, sizeLabel, sizeBox);
        row.setStyle("-fx-alignment: CENTER_LEFT;");

        Label note = new Label(
            "FIT = each image becomes a page sized to match the image dimensions.\n" +
            "A4/A3/LETTER = images are scaled to fit the selected page size.");
        note.setStyle("-fx-font-size: 10px; -fx-opacity: 0.6;");
        note.setWrapText(true);

        VBox box = new VBox(14, title, row, note);
        box.setStyle("-fx-padding: 18;");
        return box;
    }
}
