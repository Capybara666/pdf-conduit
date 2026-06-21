package com.pdfconduit.app.gui.util;

import com.pdfconduit.core.model.PageSize;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.prefs.Preferences;

/**
 * Central store for the user-configurable defaults exposed by the Settings panel:
 * default output folder, default compress target, default page size (To PDF) and
 * the post-operation auto-open behaviour. Mirrors the style of {@link Sfx} /
 * {@code ThemeManager} — a {@code final} class over a single {@link Preferences}
 * node, with defensive parsing so a corrupt stored value never breaks the app.
 *
 * <p>Theme, language and sound stay in their own managers ({@code ThemeManager},
 * {@code I18n}, {@code Sfx}); this only owns the four newer defaults.
 */
public final class Settings {

    /** What to do with the result once an operation finishes. */
    public enum AutoOpen { NONE, FILE, FOLDER }

    private static final Preferences PREFS = Preferences.userNodeForPackage(Settings.class);

    private Settings() {}

    // --- default output folder -------------------------------------------

    /**
     * The configured default output directory, or {@code null} when none is set
     * (meaning "use the computed default"). A stored path that no longer points at
     * a directory is treated as unset.
     */
    public static Path outputDir() {
        String stored = PREFS.get("outputDir", "");
        if (stored == null || stored.isBlank()) return null;
        try {
            Path p = Path.of(stored.strip());
            return Files.isDirectory(p) ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Sets (or, when blank, clears) the configured default output directory. */
    public static void setOutputDir(String path) {
        if (path == null || path.isBlank()) {
            PREFS.remove("outputDir");
        } else {
            PREFS.put("outputDir", path.strip());
        }
    }

    // --- default compress target -----------------------------------------

    public static double compressValue() { return PREFS.getDouble("compressValue", 5); }

    public static void setCompressValue(double value) { PREFS.putDouble("compressValue", value); }

    public static String compressUnit() {
        String unit = PREFS.get("compressUnit", "MB");
        return ("KB".equals(unit) || "MB".equals(unit)) ? unit : "MB";
    }

    public static void setCompressUnit(String unit) {
        PREFS.put("compressUnit", "KB".equals(unit) ? "KB" : "MB");
    }

    // --- default page size (To PDF) --------------------------------------

    public static PageSize pageSize() {
        try {
            return PageSize.valueOf(PREFS.get("pageSize", PageSize.FIT.name()));
        } catch (IllegalArgumentException e) {
            return PageSize.FIT;
        }
    }

    public static void setPageSize(PageSize size) {
        PREFS.put("pageSize", (size == null ? PageSize.FIT : size).name());
    }

    // --- auto-open result -------------------------------------------------

    public static AutoOpen autoOpen() {
        try {
            return AutoOpen.valueOf(PREFS.get("autoOpen", AutoOpen.NONE.name()));
        } catch (IllegalArgumentException e) {
            return AutoOpen.NONE;
        }
    }

    public static void setAutoOpen(AutoOpen value) {
        PREFS.put("autoOpen", (value == null ? AutoOpen.NONE : value).name());
    }
}
