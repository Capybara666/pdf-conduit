package org.example.app.gui.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DefaultLocationsTest {

    @Test
    void defaultDirEndsWithPdfConduit() {
        Path dir = DefaultLocations.defaultDir();
        assertEquals("pdf-conduit", dir.getFileName().toString());
        assertTrue(dir.isAbsolute(), "default dir should be absolute");
    }

    @Test
    void defaultFileIsResultPdfInsideDefaultDir() {
        Path file = DefaultLocations.defaultFile();
        assertEquals(DefaultLocations.DEFAULT_FILE, file.getFileName().toString());
        assertEquals("pdf_conduit_result.pdf", DefaultLocations.DEFAULT_FILE);
        assertEquals(DefaultLocations.defaultDir(), file.getParent());
    }
}
