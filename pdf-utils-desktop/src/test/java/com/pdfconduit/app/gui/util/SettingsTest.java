package com.pdfconduit.app.gui.util;

import com.pdfconduit.core.model.PageSize;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Settings} is a thin, headless store over {@link Preferences} (no JavaFX).
 * These tests round-trip each preference, check the unset defaults, and verify the
 * defensive parsing for corrupt / blank stored values. Each test snapshots and
 * restores the real preference node so it never pollutes the developer's settings.
 */
class SettingsTest {

    private static final Preferences PREFS = Preferences.userNodeForPackage(Settings.class);
    private static final String[] KEYS =
        { "outputDir", "compressValue", "compressUnit", "pageSize", "autoOpen" };

    private final Map<String, String> saved = new HashMap<>();

    @BeforeEach
    void snapshot() {
        for (String k : KEYS) {
            String v = PREFS.get(k, null);
            if (v != null) saved.put(k, v);
        }
        clearKeys();
    }

    @AfterEach
    void restore() {
        clearKeys();
        saved.forEach(PREFS::put);
    }

    private void clearKeys() {
        for (String k : KEYS) PREFS.remove(k);
    }

    // --- defaults ---------------------------------------------------------

    @Test
    void defaultsWhenUnset() {
        assertNull(Settings.outputDir());
        assertEquals(5, Settings.compressValue());
        assertEquals("MB", Settings.compressUnit());
        assertEquals(PageSize.FIT, Settings.pageSize());
        assertEquals(Settings.AutoOpen.NONE, Settings.autoOpen());
    }

    // --- round-trips ------------------------------------------------------

    @Test
    void outputDirRoundTripsAnExistingDirectory(@org.junit.jupiter.api.io.TempDir Path tmp) {
        Settings.setOutputDir(tmp.toString());
        assertEquals(tmp, Settings.outputDir());
    }

    @Test
    void blankOutputDirClearsIt(@org.junit.jupiter.api.io.TempDir Path tmp) {
        Settings.setOutputDir(tmp.toString());
        Settings.setOutputDir("   ");
        assertNull(Settings.outputDir());
    }

    @Test
    void outputDirThatIsNoLongerADirectoryReadsAsUnset() throws IOException {
        Path file = Files.createTempFile("settings-test", ".txt");
        try {
            Settings.setOutputDir(file.toString());   // a file, not a directory
            assertNull(Settings.outputDir());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void compressTargetRoundTrips() {
        Settings.setCompressValue(1.5);
        Settings.setCompressUnit("KB");
        assertEquals(1.5, Settings.compressValue());
        assertEquals("KB", Settings.compressUnit());
    }

    @Test
    void compressUnitFallsBackToMbForGarbage() {
        PREFS.put("compressUnit", "GB");
        assertEquals("MB", Settings.compressUnit());
    }

    @Test
    void pageSizeRoundTrips() {
        Settings.setPageSize(PageSize.A4);
        assertEquals(PageSize.A4, Settings.pageSize());
    }

    @Test
    void pageSizeFallsBackToFitForGarbage() {
        PREFS.put("pageSize", "B5");
        assertEquals(PageSize.FIT, Settings.pageSize());
    }

    @Test
    void autoOpenRoundTrips() {
        Settings.setAutoOpen(Settings.AutoOpen.FOLDER);
        assertEquals(Settings.AutoOpen.FOLDER, Settings.autoOpen());
    }

    @Test
    void autoOpenFallsBackToNoneForBadOrBlankStoredValue() {
        PREFS.put("autoOpen", "BANANA");
        assertEquals(Settings.AutoOpen.NONE, Settings.autoOpen());
        PREFS.put("autoOpen", "");
        assertEquals(Settings.AutoOpen.NONE, Settings.autoOpen());
    }
}
