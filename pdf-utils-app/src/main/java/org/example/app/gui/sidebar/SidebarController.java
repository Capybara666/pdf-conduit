package org.example.app.gui.sidebar;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

public class SidebarController extends VBox {

    private final Map<SidebarItem, Button> buttons = new EnumMap<>(SidebarItem.class);
    private SidebarItem active;

    public SidebarController(Consumer<SidebarItem> onSelect) {
        getStyleClass().add("sidebar");

        Label sectionLabel = new Label("OPERATIONS");
        sectionLabel.getStyleClass().add("sidebar-section-label");
        getChildren().add(sectionLabel);

        for (SidebarItem item : SidebarItem.values()) {
            if (item == SidebarItem.WIZARD) continue;
            Button btn = createItemButton(item, onSelect);
            buttons.put(item, btn);
            getChildren().add(btn);
        }

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        getChildren().add(spacer);

        Button wizardBtn = createWizardButton(onSelect);
        buttons.put(SidebarItem.WIZARD, wizardBtn);
        getChildren().add(wizardBtn);
    }

    private Button createItemButton(SidebarItem item, Consumer<SidebarItem> onSelect) {
        Button btn = new Button(item.icon + "  " + item.label);
        btn.getStyleClass().add("sidebar-item");
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setOnAction(e -> select(item, onSelect));
        return btn;
    }

    private Button createWizardButton(Consumer<SidebarItem> onSelect) {
        Button btn = new Button(SidebarItem.WIZARD.icon + "  " + SidebarItem.WIZARD.label);
        btn.getStyleClass().add("sidebar-wizard-item");
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setOnAction(e -> select(SidebarItem.WIZARD, onSelect));
        return btn;
    }

    public void select(SidebarItem item, Consumer<SidebarItem> callback) {
        if (active != null) {
            Button prev = buttons.get(active);
            if (prev != null) prev.getStyleClass().remove("active");
        }
        active = item;
        Button curr = buttons.get(item);
        if (curr != null) curr.getStyleClass().add("active");
        callback.accept(item);
    }

    public void setDisabledExceptWizard(boolean disabled) {
        for (var entry : buttons.entrySet()) {
            if (entry.getKey() != SidebarItem.WIZARD) {
                entry.getValue().setDisable(disabled);
            }
        }
    }
}
