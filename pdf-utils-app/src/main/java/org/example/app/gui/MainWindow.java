package org.example.app.gui;

import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.example.app.gui.panels.*;
import org.example.app.gui.sidebar.SidebarController;
import org.example.app.gui.sidebar.SidebarItem;
import org.example.app.gui.wizard.WizardController;
import org.example.app.gui.util.Sfx;
import org.example.app.i18n.I18n;

import java.util.EnumMap;
import java.util.Map;

public class MainWindow {

    private final Stage stage;
    private final Scene scene;
    private final Map<SidebarItem, Node> panels = new EnumMap<>(SidebarItem.class);

    private SidebarController sidebar;
    private StackPane contentArea;
    private SidebarItem currentItem = SidebarItem.MERGE;

    public MainWindow(Stage stage) {
        this.stage = stage;

        scene = new Scene(new BorderPane(), 900, 660);
        ThemeManager.applyStored(scene);

        stage.setTitle("PDF Conduit");
        stage.setMinWidth(640);
        stage.setMinHeight(400);
        stage.setScene(scene);
        applyIcons();

        buildContent();
        I18n.addListener(this::rebuild);
        sidebar.select(currentItem, this::showPanel);
    }

    /** Builds (or rebuilds) the sidebar, menu bar and content area into the scene. */
    private void buildContent() {
        contentArea = new StackPane();
        contentArea.getStyleClass().add("content-area");

        sidebar = new SidebarController(this::showPanel);

        BorderPane root = new BorderPane();
        root.setLeft(sidebar);
        root.setCenter(contentArea);
        root.setTop(buildMenuBar());
        scene.setRoot(root);
    }

    /** Rebuilds the whole UI in the new language (panels are recreated lazily). */
    private void rebuild() {
        panels.clear();
        buildContent();
        sidebar.select(currentItem, this::showPanel);
    }

    private MenuBar buildMenuBar() {
        MenuBar menuBar = new MenuBar();

        Menu themeMenu = new Menu(I18n.t("menu.theme"));
        ToggleGroup themeGroup = new ToggleGroup();
        for (ThemeManager.Theme theme : ThemeManager.Theme.values()) {
            RadioMenuItem item = new RadioMenuItem(theme.displayName);
            item.setToggleGroup(themeGroup);
            item.setSelected(theme == ThemeManager.getCurrent());
            item.setOnAction(e -> ThemeManager.apply(scene, theme));
            themeMenu.getItems().add(item);
            if (theme == ThemeManager.Theme.SYSTEM) {
                themeMenu.getItems().add(new SeparatorMenuItem());
            }
        }

        Menu langMenu = new Menu(I18n.t("menu.language"));
        ToggleGroup langGroup = new ToggleGroup();
        for (I18n.Language lang : I18n.Language.values()) {
            RadioMenuItem item = new RadioMenuItem(lang.displayName);
            item.setToggleGroup(langGroup);
            item.setSelected(lang == I18n.getCurrent());
            item.setOnAction(e -> I18n.setLanguage(lang));
            langMenu.getItems().add(item);
        }

        Menu soundMenu = new Menu(I18n.t("menu.sound"));
        CheckMenuItem soundToggle = new CheckMenuItem(I18n.t("menu.soundeffects"));
        soundToggle.setSelected(Sfx.isEnabled());
        soundToggle.setOnAction(e -> Sfx.setEnabled(soundToggle.isSelected()));
        soundMenu.getItems().add(soundToggle);

        Menu helpMenu = new Menu(I18n.t("menu.help"));
        MenuItem about = new MenuItem(I18n.t("menu.about"));
        about.setOnAction(e -> showAbout());
        helpMenu.getItems().add(about);

        menuBar.getMenus().addAll(themeMenu, langMenu, soundMenu, helpMenu);
        return menuBar;
    }

    /** Loads the bundled app icons onto the stage (taskbar / title bar). */
    private void applyIcons() {
        for (int size : new int[]{16, 24, 32, 48, 64, 128, 256}) {
            var in = getClass().getResourceAsStream("/icons/app-" + size + ".png");
            if (in != null) stage.getIcons().add(new Image(in));
        }
    }

    private void showAbout() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(stage);
        var logo = getClass().getResourceAsStream("/icons/app-64.png");
        if (logo != null) alert.setGraphic(new ImageView(new Image(logo)));
        alert.setTitle(I18n.t("about.title"));
        alert.setHeaderText("PDF Conduit 1.0.0");
        alert.setContentText(I18n.t("about.content"));
        alert.showAndWait();
    }

    private void showPanel(SidebarItem item) {
        currentItem = item;
        Node panel = panels.computeIfAbsent(item, this::createPanel);
        contentArea.getChildren().setAll(panel);
        Animations.fadeSlideIn(panel);
    }

    private Node createPanel(SidebarItem item) {
        return switch (item) {
            case MERGE    -> new MergePanel();
            case SPLIT    -> new SplitPanel();
            case COMPRESS -> new CompressPanel();
            case ROTATE   -> new RotatePanel();
            case IMAGES   -> new ImagesToPdfPanel();
            case PIPELINE -> new org.example.app.gui.pipeline.PipelineView();
            case WIZARD   -> new WizardController();
        };
    }

    public void show() {
        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        // Position BEFORE show() using the known scene size so the window maps
        // onto the primary monitor, centered — otherwise the window manager
        // places it first (often on the wrong monitor) and a post-show move can
        // be ignored if the decorated size isn't ready yet.
        stage.setX(bounds.getMinX() + (bounds.getWidth()  - scene.getWidth())  / 2);
        stage.setY(bounds.getMinY() + (bounds.getHeight() - scene.getHeight()) / 2);

        stage.show();

        // Refine now that the real decorated size is known (skip if not yet valid).
        double w = stage.getWidth(), h = stage.getHeight();
        if (!Double.isNaN(w) && !Double.isNaN(h) && w > 0 && h > 0) {
            stage.setX(bounds.getMinX() + (bounds.getWidth()  - w) / 2);
            stage.setY(bounds.getMinY() + (bounds.getHeight() - h) / 2);
        }
    }
}
