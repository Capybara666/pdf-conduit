package com.pdfconduit.core.analyze;

/**
 * The on-page location of one concrete PII occurrence, expressed in the <b>exact</b>
 * coordinate space used by {@link com.pdfconduit.core.model.RedactRegion}: displayed-page
 * points with a <b>top-left origin</b> (x grows right, y grows down) and page rotation
 * already applied. A {@link PiiRegion} can therefore be handed straight to the redact tool
 * to black out the value it covers (a "scan → pre-prepared redaction regions" flow).
 *
 * <p>Only concrete <em>value</em> findings (email, phone, IBAN, card, national ids, …) carry
 * regions; {@link PiiCategory#SPECIAL_CATEGORY} keyword signals do not.
 *
 * @param page   <b>zero-based</b> page index (note: {@link PiiFinding#page()} is 1-based)
 * @param x      left edge, points from the page's left
 * @param y      top edge, points from the page's top
 * @param width  rectangle width in points
 * @param height rectangle height in points
 */
public record PiiRegion(int page, double x, double y, double width, double height) {}
