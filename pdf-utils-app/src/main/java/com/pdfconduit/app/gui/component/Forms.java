package com.pdfconduit.app.gui.component;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import com.pdfconduit.app.gui.Ui;
import com.pdfconduit.app.i18n.I18n;

/**
 * Shared form-row helpers, so panels that don't extend {@code BasePanel} (e.g. the
 * Settings panel) produce labels and labelled fields identical to the operation
 * panels. {@code BasePanel.fieldLabel} / {@code labeledField} delegate here.
 */
public final class Forms {

    private Forms() {}

    /** A standard option label (small caption styling), re-translated live. */
    public static Label label(String i18nKey) {
        Label l = new Label();
        I18n.bindText(l::setText, i18nKey);
        l.getStyleClass().add("text-sm");
        return l;
    }

    /** A label stacked above the field it describes, with the standard gap. */
    public static VBox labeledField(String i18nKey, Node field) {
        return new VBox(Ui.LABEL_FIELD_GAP, label(i18nKey), field);
    }
}
