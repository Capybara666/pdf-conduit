package org.example.core.model;

public enum PageSize {
    FIT(0, 0),
    A4(595.28f, 841.89f),
    A3(841.89f, 1190.55f),
    LETTER(612f, 792f);

    public final float widthPt;
    public final float heightPt;

    PageSize(float widthPt, float heightPt) {
        this.widthPt = widthPt;
        this.heightPt = heightPt;
    }
}
