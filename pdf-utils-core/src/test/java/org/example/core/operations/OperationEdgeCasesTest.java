package org.example.core.operations;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.example.core.exception.PdfOperationException;
import org.example.core.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Negative paths: encrypted and damaged inputs must surface the clear
 * {@link org.example.core.util.PdfLoader} messages through real operations,
 * not raw PDFBox errors.
 */
class OperationEdgeCasesTest {

    @TempDir Path tmp;

    private Path plainPdf(String name, int pages) throws Exception {
        Path p = tmp.resolve(name);
        try (PDDocument d = new PDDocument()) {
            for (int i = 0; i < pages; i++) d.addPage(new PDPage());
            d.save(p.toFile());
        }
        return p;
    }

    private Path encryptedPdf(String name) throws Exception {
        Path plain = plainPdf("plain-" + name, 1);
        Path enc = tmp.resolve(name);
        PdfProtector.execute(new ProtectOptions(plain, "secret", null, enc));
        return enc;
    }

    private Path corruptPdf(String name) throws Exception {
        Path p = tmp.resolve(name);
        Files.writeString(p, "%PDF-1.4 this is not really a pdf");
        return p;
    }

    @Test
    void compressingAnEncryptedPdfReportsItIsProtected() throws Exception {
        Path enc = encryptedPdf("enc.pdf");
        PdfOperationException ex = assertThrows(PdfOperationException.class, () ->
            PdfCompressor.execute(new CompressOptions(enc, 100_000, tmp.resolve("out.pdf"))));
        assertTrue(ex.getMessage().toLowerCase().contains("password-protected"), ex.getMessage());
    }

    @Test
    void rotatingACorruptFileReportsItIsNotAValidPdf() throws Exception {
        Path bad = corruptPdf("bad.pdf");
        PdfOperationException ex = assertThrows(PdfOperationException.class, () ->
            PdfRotator.execute(new RotateOptions(bad, PageRange.ALL, 90, tmp.resolve("out.pdf"))));
        assertTrue(ex.getMessage().toLowerCase().contains("not a valid pdf"), ex.getMessage());
    }

    @Test
    void mergingWithAnEncryptedSourceReportsItIsProtected() throws Exception {
        Path ok = plainPdf("ok.pdf", 2);
        Path enc = encryptedPdf("enc2.pdf");
        PdfOperationException ex = assertThrows(PdfOperationException.class, () ->
            PdfMerger.execute(new MergeOptions(List.of(
                new PageSource.PdfPageSource(ok, PageRange.ALL),
                new PageSource.PdfPageSource(enc, PageRange.ALL)), tmp.resolve("out.pdf"))));
        assertTrue(ex.getMessage().toLowerCase().contains("password-protected"), ex.getMessage());
    }

    @Test
    void unlockingWithTheWrongPasswordReportsIt() throws Exception {
        Path enc = encryptedPdf("enc3.pdf");
        PdfOperationException ex = assertThrows(PdfOperationException.class, () ->
            PdfUnlocker.execute(new UnlockOptions(enc, "nope", tmp.resolve("out.pdf"))));
        assertTrue(ex.getMessage().toLowerCase().contains("wrong password"), ex.getMessage());
    }
}
