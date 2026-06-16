package org.example.app.gui.wizard;

import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

public class Step4Compression implements WizardStep {

    private final WizardModel model;

    public Step4Compression(WizardModel model) { this.model = model; }

    @Override
    public Node getContent() {
        Label title = new Label("Step 4: Compression (optional)");
        title.getStyleClass().add("panel-title");

        CheckBox enableCompress = new CheckBox("Compress output to target size");
        enableCompress.selectedProperty().bindBidirectional(model.compress);

        TextField sizeField = new TextField();
        sizeField.setPromptText("e.g. 5");
        sizeField.setDisable(true);

        ComboBox<String> unitBox = new ComboBox<>();
        unitBox.getItems().addAll("MB", "KB");
        unitBox.setValue("MB");
        unitBox.setDisable(true);

        enableCompress.selectedProperty().addListener((obs, o, enabled) -> {
            sizeField.setDisable(!enabled);
            unitBox.setDisable(!enabled);
        });

        sizeField.textProperty().addListener((obs, o, val) -> {
            try {
                double v = Double.parseDouble(val);
                model.targetSizeBytes.set(unitBox.getValue().equals("MB")
                    ? (long)(v * 1024 * 1024) : (long)(v * 1024));
            } catch (NumberFormatException ignored) {}
        });

        HBox sizeRow = new HBox(6, new Label("Target:"), sizeField, unitBox);
        sizeRow.setStyle("-fx-alignment: CENTER_LEFT;");

        VBox box = new VBox(12, title, enableCompress, sizeRow);
        box.setStyle("-fx-padding: 18;");
        return box;
    }
}
