package com.pdfconduit.app.gui.panels;

import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import com.pdfconduit.app.gui.ThemeManager;
import com.pdfconduit.app.gui.Ui;
import com.pdfconduit.app.gui.component.Forms;
import com.pdfconduit.app.gui.util.DefaultLocations;
import com.pdfconduit.app.gui.util.Settings;
import com.pdfconduit.app.gui.util.Sfx;
import com.pdfconduit.app.i18n.I18n;
import com.pdfconduit.core.model.PageSize;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

/**
 * The Settings panel: one place for every app preference, reached from the
 * sidebar (pinned to the bottom). Every control applies live and persists — there
 * is no OK/Cancel, matching how theme and language already apply immediately.
 *
 * <p>Theme, language and sound write through to their own managers; the four newer
 * defaults (output folder, compress target, page size, auto-open) write through
 * {@link Settings}. Values are read from those managers once at construction; a
 * default changed here won't retro-update an already-open operation panel (panels
 * are cached), but newly opened panels and the next launch pick it up.
 */
public final class SettingsPanel extends VBox {

    public SettingsPanel() {
        super(14);
        getStyleClass().add("panel-root");

        Label title = new Label();
        title.getStyleClass().add("panel-title");
        I18n.bindText(title::setText, "panel.SETTINGS.title");

        Label hint = new Label();
        hint.getStyleClass().add("text-caption");
        hint.setWrapText(true);
        I18n.bindText(hint::setText, "hint.SETTINGS");

        VBox rows = new VBox(Ui.OPTION_GAP,
            Forms.labeledField("settings.theme",        themeControl()),
            Forms.labeledField("settings.language",     languageControl()),
            Forms.labeledField("settings.sound",        soundControl()),
            Forms.labeledField("settings.outputfolder", outputFolderControl()),
            Forms.labeledField("settings.compresstarget", compressTargetControl()),
            Forms.labeledField("settings.pagesize",     pageSizeControl()),
            Forms.labeledField("settings.autoopen",     autoOpenControl()));

        getChildren().addAll(title, hint, rows);
    }

    // --- individual controls ---------------------------------------------

    private ComboBox<ThemeManager.Theme> themeControl() {
        ComboBox<ThemeManager.Theme> box = displayCombo(
            List.of(ThemeManager.Theme.values()), ThemeManager.getCurrent(), t -> t.displayName);
        box.valueProperty().addListener((obs, old, val) -> {
            if (val != null && getScene() != null) ThemeManager.apply(getScene(), val);
        });
        return box;
    }

    private ComboBox<I18n.Language> languageControl() {
        ComboBox<I18n.Language> box = displayCombo(
            List.of(I18n.Language.values()), I18n.getCurrent(), l -> l.displayName);
        box.valueProperty().addListener((obs, old, val) -> {
            if (val != null) I18n.setLanguage(val);
        });
        return box;
    }

    private CheckBox soundControl() {
        CheckBox box = new CheckBox();
        I18n.bindText(box::setText, "settings.sound.toggle");
        box.setSelected(Sfx.isEnabled());
        box.selectedProperty().addListener((obs, old, val) -> Sfx.setEnabled(val));
        return box;
    }

    private HBox outputFolderControl() {
        TextField field = new TextField();
        Path configured = Settings.outputDir();
        field.setText((configured != null ? configured : DefaultLocations.defaultDir()).toString());
        HBox.setHgrow(field, Priority.ALWAYS);
        field.textProperty().addListener((obs, old, val) -> Settings.setOutputDir(val));

        Button browse = new Button("…");
        browse.getStyleClass().add("btn-secondary");
        Tooltip.install(browse, new Tooltip(I18n.t("chooser.selectfolder")));
        browse.setOnAction(e -> {
            Stage stage = getScene() == null ? null : (Stage) getScene().getWindow();
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle(I18n.t("chooser.selectfolder"));
            initialDir(field.getText()).ifPresent(chooser::setInitialDirectory);
            File dir = chooser.showDialog(stage);
            if (dir != null) field.setText(dir.getAbsolutePath());   // listener persists it
        });
        return new HBox(Ui.INLINE_GAP, field, browse);
    }

    private HBox compressTargetControl() {
        TextField field = new TextField(trimNumber(Settings.compressValue()));
        field.textProperty().addListener((obs, old, val) -> {
            try {
                Settings.setCompressValue(Double.parseDouble(val.strip()));
            } catch (NumberFormatException ignored) { /* keep last valid value */ }
        });
        ComboBox<String> unit = new ComboBox<>();
        unit.getItems().addAll("MB", "KB");
        unit.setValue(Settings.compressUnit());
        unit.valueProperty().addListener((obs, old, val) -> {
            if (val != null) Settings.setCompressUnit(val);
        });
        return new HBox(Ui.INLINE_GAP, field, unit);
    }

    private ComboBox<PageSize> pageSizeControl() {
        ComboBox<PageSize> box = displayCombo(
            List.of(PageSize.values()), Settings.pageSize(), Enum::name);
        box.valueProperty().addListener((obs, old, val) -> {
            if (val != null) Settings.setPageSize(val);
        });
        return box;
    }

    private ComboBox<Settings.AutoOpen> autoOpenControl() {
        ComboBox<Settings.AutoOpen> box = i18nCombo(
            List.of(Settings.AutoOpen.values()), Settings.autoOpen(),
            a -> switch (a) {
                case NONE   -> "autoopen.none";
                case FILE   -> "autoopen.file";
                case FOLDER -> "autoopen.folder";
            });
        box.valueProperty().addListener((obs, old, val) -> {
            if (val != null) Settings.setAutoOpen(val);
        });
        return box;
    }

    // --- combo helpers ----------------------------------------------------

    /** A combo whose item text comes from a fixed (non-translated) display function. */
    private static <T> ComboBox<T> displayCombo(List<T> items, T value, Function<T, String> textFn) {
        ComboBox<T> box = new ComboBox<>();
        box.getItems().setAll(items);
        box.setValue(value);
        box.setConverter(new StringConverter<>() {
            @Override public String toString(T t) { return t == null ? "" : textFn.apply(t); }
            @Override public T fromString(String s) { return null; }
        });
        return box;
    }

    /**
     * A combo whose item text is an i18n key looked up live, re-rendering the
     * selected (button) cell on every language change so it re-translates in place.
     */
    private static <T> ComboBox<T> i18nCombo(List<T> items, T value, Function<T, String> keyFn) {
        ComboBox<T> box = new ComboBox<>();
        box.getItems().setAll(items);
        box.setValue(value);
        box.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : I18n.t(keyFn.apply(item)));
            }
        });
        box.setButtonCell(box.getCellFactory().call(null));
        // Re-render the button cell when the language changes (the dropdown cells
        // re-translate themselves each time the popup opens).
        I18n.addListener(() -> box.setButtonCell(box.getCellFactory().call(null)));
        return box;
    }

    /** An existing directory to start the chooser in: the typed path, else the default. */
    private static java.util.Optional<File> initialDir(String pathText) {
        try {
            if (pathText != null && !pathText.isBlank()) {
                Path p = Path.of(pathText.strip());
                if (Files.isDirectory(p)) return java.util.Optional.of(p.toFile());
            }
        } catch (Exception ignored) {}
        Path def = DefaultLocations.defaultDir();
        return Files.isDirectory(def) ? java.util.Optional.of(def.toFile()) : java.util.Optional.empty();
    }

    /** Renders a target value without a trailing {@code .0} (e.g. {@code 5}, {@code 1.5}). */
    private static String trimNumber(double value) {
        return value == Math.rint(value)
            ? Long.toString((long) value)
            : Double.toString(value);
    }
}
