package com.pdfconduit.web.dto;

import com.pdfconduit.core.service.OperationType;

/**
 * UI catalog entry describing one operation, projected from {@link OperationType}.
 *
 * <p>{@code available} tells the frontend whether this server can actually run the operation —
 * today only {@code ocr} can be {@code false} (when {@code pdfconduit.web.ocr.enabled} is off);
 * every other operation is always available. The UI hides unavailable operations instead of
 * exposing dead pages.
 */
public record OperationInfo(String id, String label, String cardinality, boolean multiOutput,
                            boolean available) {

    public static OperationInfo of(OperationType type, boolean available) {
        return new OperationInfo(
            type.id(), label(type), type.cardinality().name(), type.multiOutput(), available);
    }

    /** A human-readable label derived from the enum name (e.g. {@code IMAGES_TO_PDF -> "Images To Pdf"}). */
    private static String label(OperationType type) {
        String[] words = type.name().toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }
}
