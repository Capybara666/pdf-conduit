package org.example.app.gui;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
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

        Scene scene = new Scene(root, 860, 600);
        ThemeManager.applyStored(scene);

        buildMenuBar(scene, root);

        stage.setTitle("pdf-utils");
        stage.setMinWidth(640);
        stage.setMinHeight(400);
        stage.setScene(scene);

        sidebar.select(SidebarItem.MERGE, this::showPanel);
    }

    private void buildMenuBar(Scene scene, BorderPane root) {
        MenuBar menuBar = new MenuBar();
        Menu viewMenu = new Menu("View");
        MenuItem light  = new MenuItem("Light");
        MenuItem dark   = new MenuItem("Dark");
        MenuItem system = new MenuItem("System");
        light.setOnAction(e  -> ThemeManager.apply(scene, ThemeManager.Theme.LIGHT));
        dark.setOnAction(e   -> ThemeManager.apply(scene, ThemeManager.Theme.DARK));
        system.setOnAction(e -> ThemeManager.apply(scene, ThemeManager.Theme.SYSTEM));
        viewMenu.getItems().addAll(light, dark, system);
        menuBar.getMenus().add(viewMenu);
        root.setTop(menuBar);
    }

    private void showPanel(SidebarItem item) {
        Node panel = panels.computeIfAbsent(item, this::createPanel);
        contentArea.getChildren().setAll(panel);
        sidebar.setDisabledExceptWizard(item == SidebarItem.WIZARD);
    }

    private Node createPanel(SidebarItem item) {
        return switch (item) {
            case MERGE    -> new MergePanel();
            case SPLIT    -> new SplitPanel();
            case COMPRESS -> new CompressPanel();
            case ROTATE   -> new RotatePanel();
            case IMAGES   -> new ImagesToPdfPanel();
            case WIZARD   -> new WizardController(
                () -> sidebar.select(SidebarItem.MERGE, this::showPanel));
        };
    }

    public void show() { stage.show(); }
}
