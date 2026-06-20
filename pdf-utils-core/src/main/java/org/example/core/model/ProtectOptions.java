package org.example.core.model;

import java.nio.file.Path;

/**
 * Password-protect {@code input} and write {@code output}. {@code userPassword} is
 * required to open the document; {@code ownerPassword} (permissions) falls back to
 * the user password when blank.
 */
public record ProtectOptions(Path input, String userPassword, String ownerPassword, Path output) {}
