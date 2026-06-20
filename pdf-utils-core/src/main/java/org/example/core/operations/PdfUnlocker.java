package org.example.core.operations;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.example.core.exception.PdfOperationException;
import org.example.core.model.PdfResult;
import org.example.core.model.UnlockOptions;
import org.example.core.util.OutputPaths;

import java.io.IOException;

/**
 * Removes password protection from a PDF, writing a decrypted copy. The supplied
 * password must open the document. Stateless and thread-safe.
 */
public final class PdfUnlocker {

    private PdfUnlocker() {}

    public static PdfResult execute(UnlockOptions opts) throws PdfOperationException {
        String password = opts.password() == null ? "" : opts.password();
        try (PDDocument doc = Loader.loadPDF(opts.input().toFile(), password)) {
            doc.setAllSecurityToBeRemoved(true);
            OutputPaths.ensureParentDir(opts.output());
            doc.save(opts.output().toFile());
            return new PdfResult(opts.output(), doc.getNumberOfPages());
        } catch (InvalidPasswordException e) {
            throw new PdfOperationException(
                "Wrong password for " + opts.input().getFileName() + ".", e);
        } catch (IOException e) {
            throw new PdfOperationException("Unlock failed: " + e.getMessage(), e);
        }
    }
}
