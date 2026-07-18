package com.pdfconduit.web.error;

/**
 * Thrown when an OCR request arrives but OCR is unavailable — either disabled by configuration
 * ({@code pdfconduit.web.ocr.enabled=false}) or the {@code tesseract} binary is not installed.
 * Mapped to HTTP 415 (code {@code ocr_disabled}) by {@link GlobalExceptionHandler}, mirroring the
 * office-disabled path.
 */
public class OcrDisabledException extends RuntimeException {
    public OcrDisabledException() {
        super("OCR (searchable PDF) is not available on this server. It is either disabled or the "
            + "Tesseract OCR engine is not installed.");
    }
}
