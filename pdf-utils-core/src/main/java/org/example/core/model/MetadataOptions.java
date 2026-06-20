package org.example.core.model;

import java.nio.file.Path;

/**
 * Edit a PDF's document information. When {@code strip} is true every field is
 * removed (including XMP metadata) and the field values are ignored. Otherwise a
 * non-{@code null} field is applied (an empty string clears just that field) and a
 * {@code null} field is left unchanged.
 */
public record MetadataOptions(Path input, String title, String author, String subject,
                              String keywords, boolean strip, Path output) {}
