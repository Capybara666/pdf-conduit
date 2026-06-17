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

import java.util.EnumMap;
import java.util.Map;

public class MainWindow {

    private final Stage stage;
    private final SidebarController sidebar;
    private final StackPane contentArea;
    private final Map<SidebarItem, Node> panels = new EnumMap<>(SidebarItem.class);

    public MainWindow(Stage stage) {
        this.stage = stage;

        contentArea = new StackPane();
        contentArea.getStyleClass().add("content-area");

        sidebar = new SidebarController(this::showPanel);

        BorderPane root = new BorderPane();
        root.setLeft(sidebar);
        root.setCenter(contentArea);

        Scene scene = new Scene(root, 900, 660);
        ThemeManager.applyStored(scene);

        buildMenuBar(scene, root);

        stage.setTitle("PDF Conduit");
        stage.setMinWidth(640);
        stage.setMinHeight(400);
        stage.setScene(scene);
        applyIcons();

        sidebar.select(SidebarItem.MERGE, this::showPanel);
    }

    private void buildMenuBar(Scene scene, BorderPane root) {
        MenuBar menuBar = new MenuBar();
        Menu themeMenu = new Menu("Theme");
        ToggleGroup group = new ToggleGroup();

        for (ThemeManager.Theme theme : ThemeManager.Theme.values()) {
            RadioMenuItem item = new RadioMenuItem(theme.displayName);
            item.setToggleGroup(group);
            item.setSelected(theme == ThemeManager.getCurrent());
            item.setOnAction(e -> ThemeManager.apply(scene, theme));
            themeMenu.getItems().add(item);
            if (theme == ThemeManager.Theme.SYSTEM) {
                themeMenu.getItems().add(new SeparatorMenuItem());
            }
        }

        Menu helpMenu = new Menu("Help");
        MenuItem about = new MenuItem("About");
        about.setOnAction(e -> showAbout());
        helpMenu.getItems().add(about);

        menuBar.getMenus().addAll(themeMenu, helpMenu);
        root.setTop(menuBar);
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
        alert.setTitle("About PDF Conduit");
        alert.setHeaderText("PDF Conduit 1.0.0");
        alert.setContentText(
            "Merge, extract, compress and rotate PDFs, and convert images to PDF.\n\n"
            + "Built with Apache PDFBox and JavaFX.");
        alert.showAndWait();
    }

    private void showPanel(SidebarItem item) {
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
        stage.show();
        // Center on the primary monitor (done after show() so the decorated
        // window size is known).
        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        stage.setX(bounds.getMinX() + (bounds.getWidth()  - stage.getWidth())  / 2);
        stage.setY(bounds.getMinY() + (bounds.getHeight() - stage.getHeight()) / 2);
    }
}
