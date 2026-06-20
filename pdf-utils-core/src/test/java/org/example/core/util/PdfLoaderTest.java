package org.example.core.util;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.example.core.exception.PdfOperationException;
import org.example.core.model.ProtectOptions;
import org.example.core.operations.PdfProtector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PdfLoaderTest {

    @TempDir Path tmp;

    private Path plainPdf(String name) throws Exception {
        Path p = tmp.resolve(name);
        try (PDDocument d = new PDDocument()) {
            d.addPage(new PDPage());
            d.save(p.toFile());
        }
        return p;
    }

    private Path encrypted(String name, String userPassword) throws Exception {
        Path plain = plainPdf("plain-" + name);
        Path enc = tmp.resolve(name);
        PdfProtector.execute(new ProtectOptions(plain, userPassword, null, enc));
        return enc;
    }

    @Test
    void loadsAPlainPdf() throws Exception {
        try (PDDocument d = PdfLoader.load(plainPdf("ok.pdf"))) {
            assertEquals(1, d.getNumberOfPages());
        }
    }

    @Test
    void encryptedPdfReportedAsPasswordProtected() throws Exception {
        Path enc = encrypted("enc.pdf", "pw");
        PdfOperationException ex = assertThrows(PdfOperationException.class, () -> PdfLoader.load(enc));
        assertTrue(ex.getMessage().toLowerCase().contains("password-protected"), ex.getMessage());
    }

    @Test
    void wrongPasswordReported() throws Exception {
        Path enc = encrypted("enc2.pdf", "right");
        PdfOperationException ex = assertThrows(PdfOperationException.class,
            () -> PdfLoader.load(enc, "wrong"));
        assertTrue(ex.getMessage().toLowerCase().contains("wrong password"), ex.getMessage());
    }

    @Test
    void rightPasswordLoads() throws Exception {
        Path enc = encrypted("enc3.pdf", "right");
        try (PDDocument d = PdfLoader.load(enc, "right")) {
            assertEquals(1, d.getNumberOfPages());
        }
    }

    @Test
    void corruptFileReportedAsNotAValidPdf() throws Exception {
        Path bad = tmp.resolve("bad.pdf");
        Files.writeString(bad, "this is definitely not a pdf");
        PdfOperationException ex = assertThrows(PdfOperationException.class, () -> PdfLoader.load(bad));
        assertTrue(ex.getMessage().toLowerCase().contains("not a valid pdf"), ex.getMessage());
    }
}
