package com.pdfconduit.web.support;

import com.pdfconduit.core.model.ImageFormat;
import com.pdfconduit.core.model.PageSize;
import com.pdfconduit.core.model.TextFormat;

import java.util.Locale;

/** Parses loosely-typed request params (sizes, enums) into core types, with clear 400s. */
public final class Params {

    private Params() {}

    /**
     * Parses a human size — {@code 500KB}, {@code 5MB}, {@code 1.5MB}, {@code 1048576} — into
     * bytes. Plain numbers are bytes. Throws {@link IllegalArgumentException} on nonsense.
     */
    public static long parseSize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("A target size is required (e.g. 5MB, 500KB, or bytes).");
        }
        String s = raw.strip().toUpperCase(Locale.ROOT).replace(" ", "");
        long multiplier = 1;
        if (s.endsWith("KB"))      { multiplier = 1024L;                 s = s.substring(0, s.length() - 2); }
        else if (s.endsWith("MB")) { multiplier = 1024L * 1024;         s = s.substring(0, s.length() - 2); }
        else if (s.endsWith("GB")) { multiplier = 1024L * 1024 * 1024;  s = s.substring(0, s.length() - 2); }
        else if (s.endsWith("B"))  { s = s.substring(0, s.length() - 1); }
        try {
            double value = Double.parseDouble(s);
            if (value <= 0) throw new IllegalArgumentException("Target size must be positive: " + raw);
            return Math.max(1L, (long) (value * multiplier));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid size: \"" + raw + "\" (use e.g. 5MB, 500KB, or bytes).");
        }
    }

    public static PageSize pageSize(String raw, PageSize fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return PageSize.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown page size: \"" + raw + "\" (FIT, A4, A3, LETTER).");
        }
    }

    public static ImageFormat imageFormat(String raw, ImageFormat fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String s = raw.strip().toUpperCase(Locale.ROOT);
        return switch (s) {
            case "PNG" -> ImageFormat.PNG;
            case "JPG", "JPEG" -> ImageFormat.JPEG;
            default -> throw new IllegalArgumentException("Unknown image format: \"" + raw + "\" (PNG, JPG).");
        };
    }

    public static TextFormat textFormat(String raw, TextFormat fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return TextFormat.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown text format: \"" + raw + "\" (TXT, DOCX).");
        }
    }

    /** Requires a non-blank param, else a 400. */
    public static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required parameter: " + name);
        }
        return value;
    }
}
