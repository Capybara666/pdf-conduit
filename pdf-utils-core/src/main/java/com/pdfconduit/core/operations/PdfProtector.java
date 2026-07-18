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
 * Writes a password-protected (AES-encrypted) copy of a PDF. The user password
 * is required to open the document; the owner password (controlling permissions)
 * defaults to the user password when blank. The encryption strength is selectable
 * (AES-128, the default, or AES-256). Stateless and thread-safe.
 */
public final class PdfProtector {

    private PdfProtector() {}

    public static PdfResult execute(ProtectOptions opts) throws PdfOperationException {
        try (PDDocument doc = PdfLoader.load(opts.input())) {
            applyProtection(doc, opts.userPassword(), opts.ownerPassword(), opts.keyLength());
            OutputPaths.ensureParentDir(opts.output());
            doc.save(opts.output().toFile());
            return new PdfResult(opts.output(), doc.getNumberOfPages());
        } catch (IOException e) {
            throw new PdfOperationException("Protect failed: " + e.getMessage(), e);
        }
    }

    /** In-memory variant: password-protect the PDF {@code pdf} with AES-128 and return the bytes. */
    public static byte[] executeBytes(byte[] pdf, String userPassword, String ownerPassword)
            throws PdfOperationException {
        return executeBytes(pdf, userPassword, ownerPassword, 128);
    }

    /**
     * In-memory variant: password-protect the PDF {@code pdf} at the requested AES key length
     * (128 or 256 bits; other values normalise to 128) and return the encrypted bytes.
     */
    public static byte[] executeBytes(byte[] pdf, String userPassword, String ownerPassword,
                                      int keyLength) throws PdfOperationException {
        try (PDDocument doc = PdfLoader.load(pdf)) {
            applyProtection(doc, userPassword, ownerPassword, keyLength);
            return PdfLoader.toBytes(doc);
        } catch (IOException e) {
            throw new PdfOperationException("Protect failed: " + e.getMessage(), e);
        }
    }

    /**
     * The shared algorithm: install a standard AES protection policy on {@code doc}.
     * {@code keyLength} of 256 selects AES-256; any other value falls back to AES-128.
     */
    static void applyProtection(PDDocument doc, String userPassword, String ownerPassword,
                                int keyLength) throws PdfOperationException, IOException {
        String user = userPassword == null ? "" : userPassword;
        if (user.isBlank()) {
            throw new PdfOperationException("A password is required to protect a PDF.");
        }
        String owner = (ownerPassword == null || ownerPassword.isBlank()) ? user : ownerPassword;
        int bits = keyLength == 256 ? 256 : 128;

        AccessPermission permissions = new AccessPermission();
        StandardProtectionPolicy policy = new StandardProtectionPolicy(owner, user, permissions);
        policy.setEncryptionKeyLength(bits);
        policy.setPermissions(permissions);
        doc.protect(policy);
    }
}
