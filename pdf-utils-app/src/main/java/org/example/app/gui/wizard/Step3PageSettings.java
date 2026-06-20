package org.example.app.gui.wizard;

import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.example.core.model.PageSize;
import org.example.app.i18n.I18n;

public class Step3PageSettings implements WizardStep {

    private final WizardModel model;
    private Node content;

    public Step3PageSettings(WizardModel model) { this.model = model; }

    @Override
    public Node getContent() {
        if (content == null) content = build();
        return content;
    }

    private Node build() {
        Label title = new Label();
        I18n.bindText(title::setText, "wizard.step3.title");
        title.getStyleClass().add("panel-title");

        Label sizeLabel = new Label();
        I18n.bindText(sizeLabel::setText, "wizard.step3.label");
        sizeLabel.setStyle("-fx-font-size: 11px;");

        ComboBox<PageSize> sizeBox = new ComboBox<>();
        sizeBox.getItems().addAll(PageSize.values());
        sizeBox.valueProperty().bindBidirectional(model.globalPageSize);

        HBox row = new HBox(8, sizeLabel, sizeBox);
        row.setStyle("-fx-alignment: CENTER_LEFT;");

        Label note = new Label();
        I18n.bindText(note::setText, "wizard.step3.note");
        note.setStyle("-fx-font-size: 10px; -fx-opacity: 0.6;");
        note.setWrapText(true);

        VBox box = new VBox(14, title, row, note);
        box.setStyle("-fx-padding: 18;");
        return box;
    }
}
