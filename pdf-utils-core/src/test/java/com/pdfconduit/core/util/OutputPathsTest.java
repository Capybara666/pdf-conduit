package com.pdfconduit.core.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class OutputPathsTest {

    @TempDir Path tmp;

    @Test
    void returnsDesiredWhenNothingExists() {
        Path desired = tmp.resolve("report.pdf");
        assertEquals(desired, OutputPaths.uniquePath(desired));
    }

    @Test
    void insertsCounterBeforeExtensionWhenTaken() throws Exception {
        Path desired = tmp.resolve("report.pdf");
        Files.createFile(desired);

        assertEquals(tmp.resolve("report (1).pdf"), OutputPaths.uniquePath(desired));
    }

    @Test
    void skipsToFirstFreeCounter() throws Exception {
        Files.createFile(tmp.resolve("report.pdf"));
        Files.createFile(tmp.resolve("report (1).pdf"));
        Files.createFile(tmp.resolve("report (2).pdf"));

        assertEquals(tmp.resolve("report (3).pdf"),
            OutputPaths.uniquePath(tmp.resolve("report.pdf")));
    }

    @Test
    void handlesNamesWithoutExtension() throws Exception {
        Files.createFile(tmp.resolve("data"));
        assertEquals(tmp.resolve("data (1)"), OutputPaths.uniquePath(tmp.resolve("data")));
    }
}
