package com.pdfconduit.app.gui.util;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import com.pdfconduit.app.gui.component.PdfViewer;
import com.pdfconduit.app.i18n.I18n;

import java.nio.file.Path;

/**
 * Opens a PDF in a standalone, non-modal window built around the reusable
 * {@link PdfViewer}. Used to verify an operation's result in-app — page through the
 * output without leaving the app or hunting for the file on disk — while the main
 * window stays usable. Inherits the owner's theme (stylesheets) and app icons.
 */
public final class PreviewWindow {

    private PreviewWindow() {}

    /** Opens {@code pdf} in a viewer window owned by {@code owner}'s window. */
    public static void open(Node owner, Path pdf) {
        PdfViewer viewer = new PdfViewer();
        StackPane content = new StackPane(viewer);
        content.setPadding(new Insets(14));

        Scene scene = new Scene(content, 960, 780);
        Scene ownerScene = owner == null ? null : owner.getScene();
        if (ownerScene != null) scene.getStylesheets().setAll(ownerScene.getStylesheets());

        Stage stage = new Stage();
        stage.setTitle(I18n.t("preview.window.title", pdf.getFileName()));
        stage.setScene(scene);
        stage.setMinWidth(520);
        stage.setMinHeight(480);
        if (ownerScene != null) {
            Window w = ownerScene.getWindow();
            stage.initOwner(w);
            if (w instanceof Stage os) for (Image icon : os.getIcons()) stage.getIcons().add(icon);
        }
        stage.show();
        viewer.load(pdf);
    }
}
