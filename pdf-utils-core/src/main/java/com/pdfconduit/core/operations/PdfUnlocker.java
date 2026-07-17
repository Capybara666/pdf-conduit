package com.pdfconduit.core.operations;

import com.pdfconduit.core.util.PdfLoader;
import org.apache.pdfbox.pdmodel.PDDocument;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.PdfResult;
import com.pdfconduit.core.model.UnlockOptions;
import com.pdfconduit.core.util.OutputPaths;

import java.io.IOException;

/**
 * Removes password protection from a PDF, writing a decrypted copy. The supplied
 * password must open the document. Stateless and thread-safe.
 */
public final class PdfUnlocker {

    private PdfUnlocker() {}

    public static PdfResult execute(UnlockOptions opts) throws PdfOperationException {
        String password = opts.password() == null ? "" : opts.password();
        try (PDDocument doc = PdfLoader.load(opts.input(), password)) {
            doc.setAllSecurityToBeRemoved(true);
            OutputPaths.ensureParentDir(opts.output());
            doc.save(opts.output().toFile());
            return new PdfResult(opts.output(), doc.getNumberOfPages());
        } catch (IOException e) {
            throw new PdfOperationException("Unlock failed: " + e.getMessage(), e);
        }
    }

    /** In-memory variant: remove protection from {@code pdf} using {@code password}; returns decrypted bytes. */
    public static byte[] executeBytes(byte[] pdf, String password) throws PdfOperationException {
        try (PDDocument doc = PdfLoader.load(pdf, password == null ? "" : password)) {
            doc.setAllSecurityToBeRemoved(true);
            return PdfLoader.toBytes(doc);
        } catch (IOException e) {
            throw new PdfOperationException("Unlock failed: " + e.getMessage(), e);
        }
    }
}
