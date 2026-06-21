package com.pdfconduit.core.operations;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.ProtectOptions;
import com.pdfconduit.core.model.UnlockOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PdfPasswordTest {

    @TempDir Path tmp;

    @Test
    void protectThenUnlockRoundTrips() throws Exception {
        Path src = createPdf(3);
        Path locked = tmp.resolve("locked.pdf");
        Path unlocked = tmp.resolve("unlocked.pdf");

        PdfProtector.execute(new ProtectOptions(src, "secret", "", locked));

        // Locked file cannot be opened without the password...
        assertThrows(IOException.class, () -> {
            try (PDDocument d = Loader.loadPDF(locked.toFile())) { d.getNumberOfPages(); }
        });
        // ...but opens with it.
        try (PDDocument d = Loader.loadPDF(locked.toFile(), "secret")) {
            assertEquals(3, d.getNumberOfPages());
        }

        PdfUnlocker.execute(new UnlockOptions(locked, "secret", unlocked));

        // Unlocked file opens with no password.
        try (PDDocument d = Loader.loadPDF(unlocked.toFile())) {
            assertEquals(3, d.getNumberOfPages());
        }
    }

    @Test
    void unlockWithWrongPasswordFailsClearly() throws Exception {
        Path src = createPdf(1);
        Path locked = tmp.resolve("locked.pdf");
        PdfProtector.execute(new ProtectOptions(src, "secret", "", locked));

        PdfOperationException ex = assertThrows(PdfOperationException.class,
            () -> PdfUnlocker.execute(new UnlockOptions(locked, "wrong", tmp.resolve("o.pdf"))));
        assertTrue(ex.getMessage().toLowerCase().contains("password"),
            "error should mention the password, got: " + ex.getMessage());
    }

    private Path createPdf(int pages) throws IOException {
        Path path = tmp.resolve("src-" + pages + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) doc.addPage(new PDPage(PDRectangle.A4));
            doc.save(path.toFile());
        }
        return path;
    }
}
