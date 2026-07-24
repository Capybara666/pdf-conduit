package com.pdfconduit.web.dto;

import java.util.List;

/**
 * Server capability flags from {@code GET /api/capabilities}, so the frontend can adapt its UI to
 * what this deployment actually supports (instead of exposing dead inputs/pages).
 *
 * @param officeEnabled whether office/document uploads (docx/xlsx/txt/md/html/…) are converted
 *                      ({@code pdfconduit.web.office.enabled}); when {@code false} they are
 *                      rejected with 415
 * @param ocrEnabled    whether {@code /api/ocr} accepts work ({@code pdfconduit.web.ocr.enabled})
 * @param ocrLanguages  installed Tesseract language codes (e.g. {@code eng}, {@code pol}),
 *                      discovered once via {@code tesseract --list-langs}; empty when OCR is
 *                      disabled or the binary is absent
 */
public record CapabilitiesInfo(boolean officeEnabled, boolean ocrEnabled, List<String> ocrLanguages) {}
