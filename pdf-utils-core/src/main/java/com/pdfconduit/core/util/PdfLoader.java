package com.pdfconduit.core.util;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import com.pdfconduit.core.exception.PdfOperationException;

import java.io.ByteArrayOutputStream;
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

    // --- in-memory (byte[]) variants ---------------------------------------
    // Mirror the Path loaders for the stateless web backend: input from a
    // byte[] via PDFBox's in-memory Loader, output to a byte[] via save(stream).
    // There is no file name to name in the message, so the wording is generic.

    /** Loads an unprotected PDF from raw bytes. A protected file is reported as such. */
    public static PDDocument load(byte[] pdf) throws PdfOperationException {
        try {
            return Loader.loadPDF(pdf);
        } catch (InvalidPasswordException e) {
            throw new PdfOperationException(
                "The PDF is password-protected. Remove its password with Unlock first.", e);
        } catch (IOException e) {
            throw new PdfOperationException(NOT_READABLE_BYTES, e);
        }
    }

    /** Loads a protected PDF from raw bytes with {@code password} ({@code null} = empty). */
    public static PDDocument load(byte[] pdf, String password) throws PdfOperationException {
        try {
            return Loader.loadPDF(pdf, password == null ? "" : password);
        } catch (InvalidPasswordException e) {
            throw new PdfOperationException("Wrong password for the PDF.", e);
        } catch (IOException e) {
            throw new PdfOperationException(NOT_READABLE_BYTES, e);
        }
    }

    /** Serialises {@code doc} to a byte array (the in-memory analog of {@code save(File)}). */
    public static byte[] toBytes(PDDocument doc) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }

    private static final String NOT_READABLE_BYTES =
        "Could not read the PDF: the data is not a valid PDF or is damaged.";

    private static String notReadable(Path pdf) {
        return "Could not read " + pdf.getFileName()
            + ": the file is not a valid PDF or is damaged.";
    }
}
