package com.pdfconduit.app.gui.component;

import com.pdfconduit.app.gui.util.DefaultLocations;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Headless tests for the pure output-name logic shared by every panel. */
class OutputPathControlTest {

    @Test
    void usesTypedNameAndAddsPdfExtension() {
        assertEquals("report.pdf", OutputPathControl.resolveName("report", "fallback.pdf"));
    }

    @Test
    void keepsExistingPdfExtension() {
        assertEquals("report.pdf", OutputPathControl.resolveName("report.pdf", "fallback.pdf"));
    }

    @Test
    void treatsExtensionCaseInsensitively() {
        assertEquals("REPORT.PDF", OutputPathControl.resolveName("REPORT.PDF", "fallback.pdf"));
    }

    @Test
    void fallsBackToDefaultNameWhenBlank() {
        assertEquals("fallback.pdf", OutputPathControl.resolveName("   ", "fallback.pdf"));
        assertEquals("fallback.pdf", OutputPathControl.resolveName(null, "fallback.pdf"));
    }

    @Test
    void fallsBackToAppDefaultWhenEverythingBlank() {
        assertEquals(DefaultLocations.DEFAULT_FILE, OutputPathControl.resolveName(null, null));
        assertEquals(DefaultLocations.DEFAULT_FILE, OutputPathControl.resolveName("", "  "));
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertEquals("report.pdf", OutputPathControl.resolveName("  report  ", "fallback.pdf"));
    }
}
