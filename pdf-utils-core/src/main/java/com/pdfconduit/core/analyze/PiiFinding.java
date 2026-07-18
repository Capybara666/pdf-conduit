package com.pdfconduit.core.analyze;

/**
 * A single distinct piece of personal data found in a document.
 *
 * <p>Identical values (same {@link #type} and underlying value) are collapsed into
 * one finding with {@link #occurrences} counting how many times the value appeared;
 * {@link #page} is the 1-based page where it was <em>first</em> seen.
 *
 * @param type         the concrete kind of data
 * @param category     the GDPR category (mirrors {@code type.category()})
 * @param page         1-based page number of the first occurrence
 * @param maskedSample a redacted, recognisable sample that never reveals the full
 *                     value (e.g. {@code j•••@e•••.com}, {@code •••• •••• •••• 1234})
 * @param occurrences  how many times this exact value occurred across the document
 */
public record PiiFinding(
        PiiType type,
        PiiCategory category,
        int page,
        String maskedSample,
        int occurrences) {
}
