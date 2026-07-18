package com.pdfconduit.core.model;

/**
 * The grid presets for N-up imposition: how many source pages are placed on one
 * output sheet, arranged as {@code cols × rows} (reading order left→right,
 * top→bottom). The sheet is auto-oriented (landscape when {@code cols > rows},
 * portrait when {@code rows > cols}) so the cells roughly match the source aspect.
 */
public enum NupLayout {
    TWO_UP  ("2up", 2, 1),
    FOUR_UP ("4up", 2, 2),
    SIX_UP  ("6up", 2, 3),
    EIGHT_UP("8up", 2, 4),
    NINE_UP ("9up", 3, 3);

    private final String id;
    private final int cols;
    private final int rows;

    NupLayout(String id, int cols, int rows) {
        this.id = id;
        this.cols = cols;
        this.rows = rows;
    }

    /** Stable identifier for JSON / CLI / web (e.g. {@code 4up}). */
    public String id() { return id; }

    public int cols() { return cols; }

    public int rows() { return rows; }

    /** Source pages placed on one output sheet. */
    public int perSheet() { return cols * rows; }

    /**
     * Resolves a layout from its {@link #id()} or enum name (case-insensitive),
     * falling back to {@link #TWO_UP} for a blank/unknown value.
     */
    public static NupLayout fromId(String value) {
        if (value == null || value.isBlank()) return TWO_UP;
        String v = value.trim().toLowerCase(java.util.Locale.ROOT);
        for (NupLayout l : values()) {
            if (l.id.equals(v) || l.name().toLowerCase(java.util.Locale.ROOT).equals(v)) return l;
        }
        return TWO_UP;
    }
}
