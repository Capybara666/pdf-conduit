package com.pdfconduit.app.gui.wizard;

import javafx.scene.Node;

public interface WizardStep {
    Node getContent();
    default void onFinish() {}
}
