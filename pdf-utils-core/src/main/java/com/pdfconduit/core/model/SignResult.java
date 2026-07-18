package com.pdfconduit.core.model;

import java.nio.file.Path;

/**
 * Outcome of a Fill &amp; Sign run: where it was written, the page count, how many
 * signature placements were stamped and how many AcroForm fields were filled.
 */
public record SignResult(Path output, int pages, int placementsApplied, int fieldsFilled) {}
