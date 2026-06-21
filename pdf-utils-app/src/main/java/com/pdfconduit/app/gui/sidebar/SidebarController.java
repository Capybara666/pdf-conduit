package com.pdfconduit.app.gui.sidebar;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import com.pdfconduit.app.i18n.I18n;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

public class SidebarController extends VBox {

    private final Map<SidebarItem, Button> buttons = new EnumMap<>(SidebarItem.class);
    private SidebarItem active;

    public SidebarController(Consumer<SidebarItem> onSelect) {
        getStyleClass().add("sidebar");
        setSpacing(2);

        getChildren().add(buildBrand());

        Label sectionLabel = new Label();
        I18n.bindText(sectionLabel::setText, "sidebar.operations");
        sectionLabel.getStyleClass().add("sidebar-section-label");
        getChildren().add(sectionLabel);

        for (SidebarItem item : SidebarItem.values()) {
            Button btn = createItemButton(item, onSelect);
            buttons.put(item, btn);
            getChildren().add(btn);
        }
    }

    private HBox buildBrand() {
        ImageView logo = new ImageView();
        var in = getClass().getResourceAsStream("/icons/app-32.png");
        if (in != null) {
            logo.setImage(new Image(in));
            logo.setFitWidth(22);
            logo.setFitHeight(22);
            logo.setPreserveRatio(true);
        }
        Label name = new Label("PDF Conduit");
        name.getStyleClass().add("sidebar-brand");
        HBox brand = new HBox(8, logo, name);
        brand.getStyleClass().add("sidebar-brand-row");
        brand.setAlignment(Pos.CENTER_LEFT);
        return brand;
    }

    private Button createItemButton(SidebarItem item, Consumer<SidebarItem> onSelect) {
        Button btn = new Button();
        I18n.bindText(btn::setText, "sidebar." + item.name());
        btn.setGraphic(com.pdfconduit.app.gui.icon.Icons.of(item, 18));
        btn.setGraphicTextGap(10);
        btn.getStyleClass().add("sidebar-item");
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setOnAction(e -> select(item, onSelect));
        com.pdfconduit.app.gui.Animations.installHoverScale(btn, 1.03);

        // A discoverability tooltip: the operation's hint where one exists, else its
        // panel title, else its label — all already translated, so no new keys.
        Tooltip tip = new Tooltip();
        tip.setShowDelay(Duration.millis(400));
        tip.setWrapText(true);
        tip.setMaxWidth(260);
        I18n.bindText(tip::setText, tooltipKey(item));
        btn.setTooltip(tip);
        return btn;
    }

    /** Best existing (already-translated) key to describe an item: hint → panel title → label. */
    private static String tooltipKey(SidebarItem item) {
        if (I18n.containsKey("hint." + item.name())) return "hint." + item.name();
        if (I18n.containsKey("panel." + item.name() + ".title")) return "panel." + item.name() + ".title";
        return "sidebar." + item.name();
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
}
