package org.example.app.gui.wizard;

import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.example.app.i18n.I18n;

public class Step4Compression implements WizardStep {

    private final WizardModel model;
    private Node content;

    public Step4Compression(WizardModel model) { this.model = model; }

    @Override
    public Node getContent() {
        if (content == null) content = build();
        return content;
    }

    private Node build() {
        Label title = new Label();
        I18n.bindText(title::setText, "wizard.step4.title");
        title.getStyleClass().add("panel-title");

        CheckBox enableCompress = new CheckBox();
        I18n.bindText(enableCompress::setText, "wizard.step4.enable");
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

        Runnable recompute = () -> {
            try {
                double v = Double.parseDouble(sizeField.getText());
                model.targetSizeBytes.set(unitBox.getValue().equals("MB")
                    ? (long)(v * 1024 * 1024) : (long)(v * 1024));
            } catch (NumberFormatException ignored) {}
        };
        sizeField.textProperty().addListener((obs, o, val) -> recompute.run());
        unitBox.valueProperty().addListener((obs, o, val) -> recompute.run());

        Label targetLabel = new Label();
        I18n.bindText(targetLabel::setText, "wizard.step4.target");
        HBox sizeRow = new HBox(6, targetLabel, sizeField, unitBox);
        sizeRow.setStyle("-fx-alignment: CENTER_LEFT;");

        VBox box = new VBox(12, title, enableCompress, sizeRow);
        box.setStyle("-fx-padding: 18;");
        return box;
    }
}
