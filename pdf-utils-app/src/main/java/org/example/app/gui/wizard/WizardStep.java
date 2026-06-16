package org.example.app.gui.wizard;

import javafx.scene.Node;

public interface WizardStep {
    Node getContent();
    default void onFinish() {}
}
