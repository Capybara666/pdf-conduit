package com.pdfconduit.app.gui.util;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;
import com.pdfconduit.app.i18n.I18n;
import com.pdfconduit.core.util.OutputPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Guards against silently overwriting an existing output file. When the chosen
 * destination already exists, asks the user whether to overwrite it, save under a
 * non-clobbering {@code " (n)"} name, or cancel — returning the path to write to,
 * or empty if the run should be abandoned. A free path is returned without asking.
 */
public final class OutputGuard {

    private OutputGuard() {}

    /** Confirms {@code desired}; returns the path to write (possibly renamed), or empty to cancel. */
    public static Optional<Path> confirm(Node owner, Path desired) {
        if (desired == null || !Files.exists(desired)) return Optional.ofNullable(desired);

        // A saved default skips the prompt entirely.
        switch (Settings.overwriteMode()) {
            case OVERWRITE -> { return Optional.of(desired); }
            case RENAME    -> { return Optional.of(OutputPaths.uniquePath(desired)); }
            case ASK       -> { /* fall through to the dialog */ }
        }

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        Scene ownerScene = owner == null ? null : owner.getScene();
        if (ownerScene != null) {
            Window window = ownerScene.getWindow();
            if (window != null) alert.initOwner(window);
            // Inherit the app theme so the dialog matches the window.
            alert.getDialogPane().getStylesheets().setAll(ownerScene.getStylesheets());
        }
        alert.setTitle(I18n.t("overwrite.title"));
        alert.setHeaderText(I18n.t("overwrite.header", desired.getFileName().toString()));
        alert.setContentText(I18n.t("overwrite.content"));

        ButtonType overwrite = new ButtonType(I18n.t("overwrite.overwrite"), ButtonBar.ButtonData.OK_DONE);
        ButtonType rename = new ButtonType(I18n.t("overwrite.rename"), ButtonBar.ButtonData.OTHER);
        ButtonType cancel = new ButtonType(I18n.t("btn.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(rename, overwrite, cancel);

        Optional<ButtonType> choice = alert.showAndWait();
        if (choice.isEmpty() || choice.get() == cancel) return Optional.empty();
        if (choice.get() == rename) return Optional.of(OutputPaths.uniquePath(desired));
        return Optional.of(desired);
    }
}
