package com.pdfconduit.app.gui;

import javafx.application.Platform;
import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.robot.Robot;
import javafx.stage.Screen;
import javafx.stage.Stage;
import com.pdfconduit.app.gui.panels.*;
import com.pdfconduit.app.gui.sidebar.SidebarController;
import com.pdfconduit.app.gui.sidebar.SidebarItem;
import com.pdfconduit.app.gui.wizard.WizardController;
import com.pdfconduit.app.i18n.I18n;

import java.util.EnumMap;
import java.util.Map;

public class MainWindow {

    private static final java.util.prefs.Preferences PREFS =
        java.util.prefs.Preferences.userNodeForPackage(MainWindow.class);

    private final Stage stage;
    private final Scene scene;
    private final Map<SidebarItem, Node> panels = new EnumMap<>(SidebarItem.class);

    private SidebarController sidebar;
    private StackPane contentArea;
    private BorderPane root;
    private SidebarItem currentItem = SidebarItem.MERGE;

    public MainWindow(Stage stage) {
        this.stage = stage;

        scene = new Scene(new BorderPane(), 1280, 800);
        ThemeManager.applyStored(scene);

        stage.setTitle("PDF Conduit");
        stage.setMinWidth(820);
        stage.setMinHeight(560);
        stage.setScene(scene);
        applyIcons();

        buildContent();
        // Changing language must NOT tear the UI down — that would discard the
        // user's work (loaded files, the pipeline graph, wizard progress). The
        // sidebar, panels, pipeline and wizard each re-translate themselves in
        // place via I18n.bindText; here we only swap the stateless menu bar,
        // whose radio selections track the current language/theme.
        I18n.addListener(() -> root.setTop(buildMenuBar()));
        sidebar.select(currentItem, this::showPanel);
    }

    /** Builds the sidebar, menu bar and content area into the scene (once). */
    private void buildContent() {
        contentArea = new StackPane();
        contentArea.getStyleClass().add("content-area");

        sidebar = new SidebarController(this::showPanel);

        root = new BorderPane();
        root.setLeft(sidebar);
        root.setCenter(contentArea);
        root.setTop(buildMenuBar());
        scene.setRoot(root);
    }

    private MenuBar buildMenuBar() {
        MenuBar menuBar = new MenuBar();

        // Theme / Language / Sound now live in the Settings panel (sidebar bottom);
        // the menu bar keeps only Help → About.
        Menu helpMenu = new Menu(I18n.t("menu.help"));
        MenuItem about = new MenuItem(I18n.t("menu.about"));
        about.setOnAction(e -> showAbout());
        helpMenu.getItems().add(about);

        menuBar.getMenus().add(helpMenu);
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
        ButtonType licenses = new ButtonType(I18n.t("about.licenses"), ButtonBar.ButtonData.HELP_2);
        alert.getButtonTypes().add(licenses);
        alert.showAndWait().filter(b -> b == licenses).ifPresent(b -> showLicenses());
    }

    /** A scrollable, read-only view of the bundled third-party license notices. */
    private void showLicenses() {
        TextArea area = new TextArea(loadResource("/legal/third-party-licenses.txt"));
        area.setEditable(false);
        area.setWrapText(false);
        area.setStyle("-fx-font-family: monospace;");

        Stage dialog = new Stage();
        dialog.initOwner(stage);
        dialog.setTitle(I18n.t("about.licenses"));
        Scene dlgScene = new Scene(new StackPane(area), 760, 620);
        dlgScene.getStylesheets().setAll(scene.getStylesheets());
        dialog.setScene(dlgScene);
        for (Image icon : stage.getIcons()) dialog.getIcons().add(icon);
        dialog.show();
    }

    /** Reads a UTF-8 text resource from the classpath, or a short fallback on failure. */
    private String loadResource(String path) {
        try (var in = getClass().getResourceAsStream(path)) {
            if (in == null) return path + " not found.";
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return "Could not load " + path + ": " + e.getMessage();
        }
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
            case ARRANGE  -> new ArrangePanel();
            case IMAGES   -> new ImagesToPdfPanel();
            case PROTECT  -> new ProtectPanel();
            case UNLOCK   -> new UnlockPanel();
            case METADATA -> new MetadataPanel();
            case WATERMARK -> new WatermarkPanel();
            case PIPELINE -> new com.pdfconduit.app.gui.pipeline.PipelineView();
            case WIZARD   -> new WizardController();
            case SETTINGS -> new SettingsPanel();
        };
    }

    public void show() {
        restoreSize();
        stage.show();

        // Always open centred on the monitor under the cursor — the screen the
        // user is actually working on. Done after show() (via runLater) so the
        // decorated size is known and the window manager honours the move;
        // pre-show positioning is unreliable on some Linux WMs.
        Platform.runLater(this::centerOnCursorScreen);
        // Remember the size (not the position) for next time.
        stage.setOnHidden(e -> saveSize());
    }

    private void centerOnCursorScreen() {
        Rectangle2D b = cursorScreen().getVisualBounds();
        double w = stage.getWidth(), h = stage.getHeight();
        if (Double.isNaN(w) || w <= 0) { w = scene.getWidth(); h = scene.getHeight(); }
        stage.setX(b.getMinX() + (b.getWidth()  - w) / 2);
        stage.setY(b.getMinY() + (b.getHeight() - h) / 2);
    }

    /** Restores the saved window size (position is always re-centred on launch). */
    private void restoreSize() {
        double w = PREFS.getDouble("win.w", Double.NaN);
        double h = PREFS.getDouble("win.h", Double.NaN);
        if (!Double.isNaN(w) && !Double.isNaN(h) && w >= 200 && h >= 200) {
            stage.setWidth(w);
            stage.setHeight(h);
        }
    }

    private void saveSize() {
        if (stage.isMaximized() || stage.isIconified()) return;   // keep the restorable size
        double w = stage.getWidth(), h = stage.getHeight();
        if (Double.isNaN(w) || w <= 0) return;
        PREFS.putDouble("win.w", w);
        PREFS.putDouble("win.h", h);
    }

    /** The screen the mouse pointer is currently on, falling back to primary. */
    private Screen cursorScreen() {
        try {
            Point2D p = new Robot().getMousePosition();
            var screens = Screen.getScreensForRectangle(p.getX(), p.getY(), 1, 1);
            if (!screens.isEmpty()) return screens.get(0);
        } catch (Exception ignored) {}
        return Screen.getPrimary();
    }
}
