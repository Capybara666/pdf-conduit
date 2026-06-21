package org.example.app.gui;

/**
 * Shared UI spacing constants, so every panel keeps the same vertical/horizontal
 * rhythm. Tweak here to retune the whole app's option layout at once.
 */
public final class Ui {

    private Ui() {}

    /** Gap between option groups within a panel's options area. */
    public static final double OPTION_GAP = 10;

    /** Gap between a label and the field it describes. */
    public static final double LABEL_FIELD_GAP = 4;

    /** Gap between a field and an adjacent control (combo box, button, second field). */
    public static final double INLINE_GAP = 8;
}
