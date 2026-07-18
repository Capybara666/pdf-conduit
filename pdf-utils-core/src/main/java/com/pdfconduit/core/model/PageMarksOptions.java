package com.pdfconduit.core.model;

import java.nio.file.Path;

/**
 * Draw page numbers and/or header/footer text onto every page of {@code input}.
 *
 * <p>Six independent text slots are stamped: a header (top) and footer (bottom), each with a
 * left / center / right slot. Every slot is arbitrary text that may contain the tokens
 * {@code {page}} (this page's number), {@code {n}} (alias of {@code {page}}), {@code {pages}}
 * (total page count) and {@code {date}} (today's ISO date). The displayed page number is
 * {@code startNumber + pageIndex}; when {@code numberPrefix} is non-blank it renders Bates-style
 * (prefix + the number zero-padded to six digits, e.g. {@code ACME-000042}).
 *
 * <p>{@code fontSize} is the text size in points and {@code margin} the inset from the page edge
 * (also points). {@code skipFirstPage} leaves the first page unstamped (numbering still advances,
 * so page 2 shows {@code startNumber + 1}). Drawing is done in an appended content stream, so the
 * existing page content is never disturbed, and page rotation / crop-box origin are honoured.
 */
public record PageMarksOptions(Path input,
                               String headerLeft, String headerCenter, String headerRight,
                               String footerLeft, String footerCenter, String footerRight,
                               float fontSize, float margin, boolean skipFirstPage,
                               int startNumber, String numberPrefix, Path output) {
}
