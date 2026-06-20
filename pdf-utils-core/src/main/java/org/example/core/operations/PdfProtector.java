package org.example.core.operations;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.example.core.exception.PdfOperationException;
import org.example.core.model.PdfResult;
import org.example.core.model.ProtectOptions;
import org.example.core.util.OutputPaths;

import java.io.IOException;

/**
 * Writes a password-protected (AES-128 encrypted) copy of a PDF. The user password
 * is required to open the document; the owner password (controlling permissions)
 * defaults to the user password when blank. Stateless and thread-safe.
 */
public final class PdfProtector {

    private PdfProtector() {}

    public static PdfResult execute(ProtectOptions opts) throws PdfOperationException {
        String user = opts.userPassword() == null ? "" : opts.userPassword();
        if (user.isBlank()) {
            throw new PdfOperationException("A password is required to protect a PDF.");
        }
        String owner = (opts.ownerPassword() == null || opts.ownerPassword().isBlank())
            ? user : opts.ownerPassword();

        try (PDDocument doc = Loader.loadPDF(opts.input().toFile())) {
            AccessPermission permissions = new AccessPermission();
            StandardProtectionPolicy policy = new StandardProtectionPolicy(owner, user, permissions);
            policy.setEncryptionKeyLength(128);
            policy.setPermissions(permissions);
            doc.protect(policy);

            OutputPaths.ensureParentDir(opts.output());
            doc.save(opts.output().toFile());
            return new PdfResult(opts.output(), doc.getNumberOfPages());
        } catch (IOException e) {
            throw new PdfOperationException("Protect failed: " + e.getMessage(), e);
        }
    }
}
