package com.pdfconduit.core.operations;

import com.pdfconduit.core.util.PdfLoader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.PdfResult;
import com.pdfconduit.core.model.ProtectOptions;
import com.pdfconduit.core.util.OutputPaths;

import java.io.IOException;

/**
 * Writes a password-protected (AES-128 encrypted) copy of a PDF. The user password
 * is required to open the document; the owner password (controlling permissions)
 * defaults to the user password when blank. Stateless and thread-safe.
 */
public final class PdfProtector {

    private PdfProtector() {}

    public static PdfResult execute(ProtectOptions opts) throws PdfOperationException {
        try (PDDocument doc = PdfLoader.load(opts.input())) {
            applyProtection(doc, opts.userPassword(), opts.ownerPassword());
            OutputPaths.ensureParentDir(opts.output());
            doc.save(opts.output().toFile());
            return new PdfResult(opts.output(), doc.getNumberOfPages());
        } catch (IOException e) {
            throw new PdfOperationException("Protect failed: " + e.getMessage(), e);
        }
    }

    /** In-memory variant: password-protect the PDF {@code pdf} and return the encrypted bytes. */
    public static byte[] executeBytes(byte[] pdf, String userPassword, String ownerPassword)
            throws PdfOperationException {
        try (PDDocument doc = PdfLoader.load(pdf)) {
            applyProtection(doc, userPassword, ownerPassword);
            return PdfLoader.toBytes(doc);
        } catch (IOException e) {
            throw new PdfOperationException("Protect failed: " + e.getMessage(), e);
        }
    }

    /** The shared algorithm: install an AES-128 protection policy on {@code doc}. */
    static void applyProtection(PDDocument doc, String userPassword, String ownerPassword)
            throws PdfOperationException, IOException {
        String user = userPassword == null ? "" : userPassword;
        if (user.isBlank()) {
            throw new PdfOperationException("A password is required to protect a PDF.");
        }
        String owner = (ownerPassword == null || ownerPassword.isBlank()) ? user : ownerPassword;

        AccessPermission permissions = new AccessPermission();
        StandardProtectionPolicy policy = new StandardProtectionPolicy(owner, user, permissions);
        policy.setEncryptionKeyLength(128);
        policy.setPermissions(permissions);
        doc.protect(policy);
    }
}
