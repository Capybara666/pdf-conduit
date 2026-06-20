package org.example.core.util;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.example.core.exception.PdfOperationException;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Loads a PDF, translating PDFBox's low-level failures into clear, user-facing
 * {@link PdfOperationException} messages — password-protected, wrong password, or
 * damaged / not-a-PDF. Every operation loads through here so the messages are
 * consistent across the CLI, GUI and pipeline (rather than leaking raw PDFBox text).
 */
public final class PdfLoader {

    private PdfLoader() {}

    /** Loads an unprotected PDF. A password-protected file is reported as such. */
    public static PDDocument load(Path pdf) throws PdfOperationException {
        try {
            return Loader.loadPDF(pdf.toFile());
        } catch (InvalidPasswordException e) {
            throw new PdfOperationException(
                pdf.getFileName() + " is password-protected. Remove its password with Unlock first.", e);
        } catch (IOException e) {
            throw new PdfOperationException(notReadable(pdf), e);
        }
    }

    /** Loads a protected PDF with {@code password} (treats {@code null} as empty). */
    public static PDDocument load(Path pdf, String password) throws PdfOperationException {
        try {
            return Loader.loadPDF(pdf.toFile(), password == null ? "" : password);
        } catch (InvalidPasswordException e) {
            throw new PdfOperationException("Wrong password for " + pdf.getFileName() + ".", e);
        } catch (IOException e) {
            throw new PdfOperationException(notReadable(pdf), e);
        }
    }

    private static String notReadable(Path pdf) {
        return "Could not read " + pdf.getFileName()
            + ": the file is not a valid PDF or is damaged.";
    }
}
