package com.pdfconduit.app.gui.component;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import com.pdfconduit.app.gui.Ui;
import com.pdfconduit.app.gui.util.DefaultLocations;
import com.pdfconduit.app.i18n.I18n;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Reusable output destination control: an output folder plus — for single-output
 * runs — a file name. Shared by every operation panel ({@code BasePanel}) and the
 * Arrange panel, so output handling stays identical across the app.
 *
 * <p>The name field auto-fills from a suggestion unless the user has typed their
 * own; {@link #setSingleOutput(boolean)} hides the name for multi-output / folder
 * runs without changing the control's shape.
 */
public final class OutputPathControl extends VBox {

    private final TextField folderField = new TextField();
    private final TextField nameField = new TextField();
    private final Label nameLabel = new Label();
    private String lastAutoName = "";

    public OutputPathControl() {
        super(Ui.LABEL_FIELD_GAP);

        I18n.bindText(folderField::setPromptText, "output.folder.prompt");
        folderField.setText(DefaultLocations.defaultDir().toString());
        HBox.setHgrow(folderField, Priority.ALWAYS);
        Button browseBtn = new Button("…");
        browseBtn.getStyleClass().add("btn-secondary");
        browseBtn.setOnAction(e -> browseFolder());
        HBox folderRow = new HBox(Ui.INLINE_GAP, folderField, browseBtn);

        I18n.bindText(nameField::setPromptText, "output.file.prompt");
        nameField.setText(DefaultLocations.DEFAULT_FILE);
        lastAutoName = nameField.getText();
        HBox.setHgrow(nameField, Priority.ALWAYS);
        // The name label + field only show for single-output runs.
        nameLabel.managedProperty().bind(nameLabel.visibleProperty());
        nameField.managedProperty().bind(nameField.visibleProperty());

        Label folderLabel = new Label();
        I18n.bindText(folderLabel::setText, "output.folder");
        I18n.bindText(nameLabel::setText, "output.name");

        getChildren().addAll(folderLabel, folderRow, nameLabel, nameField);
    }

    /** Shows or hides the file-name field (hidden for multi-output / folder-only runs). */
    public void setSingleOutput(boolean single) {
        nameLabel.setVisible(single);
        nameField.setVisible(single);
    }

    /** Auto-fills the name field from {@code name}, unless the user typed their own. */
    public void suggestName(String name) {
        String current = nameField.getText();
        if (current == null || current.isBlank() || current.equals(lastAutoName)) {
            nameField.setText(name);
            lastAutoName = name;
        }
    }

    /** The chosen output folder, or the default output dir when blank. */
    public Path outputDir() {
        String folder = folderField.getText();
        return (folder == null || folder.isBlank())
            ? DefaultLocations.defaultDir() : Path.of(folder.strip());
    }

    /**
     * The single-output destination: the chosen folder + file name. The name
     * defaults to {@code defaultName} when left blank, and always ends in {@code .pdf}.
     */
    public Path resolveOutput(String defaultName) {
        return outputDir().resolve(resolveName(nameField.getText(), defaultName));
    }

    /**
     * The output file name: the typed value, else {@code defaultName}, else the app
     * default; trimmed and always ending in {@code .pdf}. Pure (no UI state) so it
     * can be unit-tested headlessly.
     */
    static String resolveName(String typed, String defaultName) {
        String name = (typed == null || typed.isBlank()) ? defaultName : typed;
        if (name == null || name.isBlank()) name = DefaultLocations.DEFAULT_FILE;
        name = name.strip();
        if (!name.toLowerCase().endsWith(".pdf")) name = name + ".pdf";
        return name;
    }

    private void browseFolder() {
        Stage stage = (Stage) getScene().getWindow();
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.t("chooser.selectfolder"));
        initialDir(folderField.getText()).ifPresent(chooser::setInitialDirectory);
        File dir = chooser.showDialog(stage);
        if (dir != null) folderField.setText(dir.getAbsolutePath());
    }

    /** An existing directory to start a chooser in: the given path, else the default output dir. */
    private Optional<File> initialDir(String pathText) {
        try {
            if (pathText != null && !pathText.isBlank()) {
                Path p = Path.of(pathText);
                if (Files.isDirectory(p)) return Optional.of(p.toFile());
            }
        } catch (Exception ignored) {}
        Path def = DefaultLocations.defaultDir();
        return Files.isDirectory(def) ? Optional.of(def.toFile()) : Optional.empty();
    }
}
