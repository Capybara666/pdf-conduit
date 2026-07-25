package com.pdfconduit.core.model;

/**
 * In-memory counterpart of {@link RedactResult}: the redacted PDF bytes plus what was
 * <b>actually</b> applied. The counts are part of the contract, not a diagnostic — a caller that
 * names its output {@code *_redacted.pdf} must be able to prove something was really blacked out,
 * because a redaction that silently applies nothing is a data leak wearing a safe filename.
 *
 * @param data            the redacted PDF
 * @param redactedPages   how many pages were rasterised and painted
 * @param redactedRegions how many rectangles were painted
 */
public record RedactBytesResult(byte[] data, int redactedPages, int redactedRegions) {}
