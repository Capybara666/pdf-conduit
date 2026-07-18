package com.pdfconduit.core.analyze;

import java.util.List;

/**
 * A single distinct piece of personal data found in a document.
 *
 * <p>Identical values (same {@link #type} and underlying value) are collapsed into
 * one finding with {@link #occurrences} counting how many times the value appeared;
 * {@link #page} is the 1-based page where it was <em>first</em> seen.
 *
 * <p>{@link #regions} carries the on-page bounding box of every occurrence — one entry
 * per occurrence, each in the redact-tool coordinate space (see {@link PiiRegion}) — so a
 * scan result can be fed straight into a "pre-prepared redaction regions" flow. Only
 * concrete <em>value</em> findings produce regions; {@link PiiCategory#SPECIAL_CATEGORY}
 * keyword signals are context, not values to black out, so their {@code regions} is empty.
 * The list may also be empty when a value's glyphs could not be located (best effort).
 *
 * @param type         the concrete kind of data
 * @param category     the GDPR category (mirrors {@code type.category()})
 * @param page         1-based page number of the first occurrence
 * @param maskedSample a redacted, recognisable sample that never reveals the full
 *                     value (e.g. {@code j•••@e•••.com}, {@code •••• •••• •••• 1234})
 * @param occurrences  how many times this exact value occurred across the document
 * @param regions      per-occurrence bounding boxes (0-based page), possibly empty
 */
public record PiiFinding(
        PiiType type,
        PiiCategory category,
        int page,
        String maskedSample,
        int occurrences,
        List<PiiRegion> regions) {

    public PiiFinding {
        regions = List.copyOf(regions);
    }
}
