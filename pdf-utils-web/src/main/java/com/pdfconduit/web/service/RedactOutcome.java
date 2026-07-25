package com.pdfconduit.web.service;

import com.pdfconduit.core.service.NamedBytes;

/**
 * The result of a redaction endpoint: the output file plus <b>what was actually blacked out</b>.
 * The counts are surfaced to the client as {@code X-Redacted-Pages} / {@code X-Redacted-Regions}
 * so a caller never has to take a {@code *_redacted.pdf} filename on trust — a redaction that
 * applied nothing is refused before it can become a download.
 */
public record RedactOutcome(NamedBytes file, int redactedPages, int redactedRegions) {}
