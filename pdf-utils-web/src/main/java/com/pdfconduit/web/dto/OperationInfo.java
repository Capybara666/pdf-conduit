package com.pdfconduit.web.dto;

import com.pdfconduit.core.service.OperationType;

/** UI catalog entry describing one operation, projected from {@link OperationType}. */
public record OperationInfo(String id, String label, String cardinality, boolean multiOutput) {

    public static OperationInfo of(OperationType type) {
        return new OperationInfo(type.id(), label(type), type.cardinality().name(), type.multiOutput());
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
