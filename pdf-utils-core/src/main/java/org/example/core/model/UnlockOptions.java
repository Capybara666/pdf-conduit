package org.example.core.model;

import java.nio.file.Path;

/** Remove password protection from {@code input} (using {@code password}) into {@code output}. */
public record UnlockOptions(Path input, String password, Path output) {}
