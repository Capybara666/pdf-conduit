package com.pdfconduit.core.model;

import java.nio.file.Path;

/**
 * Password-protect {@code input} and write {@code output}. {@code userPassword} is
 * required to open the document; {@code ownerPassword} (permissions) falls back to
 * the user password when blank. {@code keyLength} selects the AES key size in bits
 * (128 or 256); any other value normalises to 128 for maximum reader compatibility.
 */
public record ProtectOptions(Path input, String userPassword, String ownerPassword, Path output,
                             int keyLength) {

    /** Back-compatible constructor: defaults to AES-128 encryption. */
    public ProtectOptions(Path input, String userPassword, String ownerPassword, Path output) {
        this(input, userPassword, ownerPassword, output, 128);
    }
}
