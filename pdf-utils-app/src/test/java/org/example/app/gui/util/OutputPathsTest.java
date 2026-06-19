package org.example.app.gui.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class OutputPathsTest {

    @Test
    void defaultDirEndsWithPdfConduit() {
        Path dir = OutputPaths.defaultDir();
        assertEquals("pdf-conduit", dir.getFileName().toString());
        assertTrue(dir.isAbsolute(), "default dir should be absolute");
    }

    @Test
    void defaultFileIsResultPdfInsideDefaultDir() {
        Path file = OutputPaths.defaultFile();
        assertEquals(OutputPaths.DEFAULT_FILE, file.getFileName().toString());
        assertEquals("pdf_conduit_result.pdf", OutputPaths.DEFAULT_FILE);
        assertEquals(OutputPaths.defaultDir(), file.getParent());
    }
}
