package com.pdfconduit.core.model;

import java.util.List;

/**
 * One enumerated AcroForm field of a PDF, as surfaced by
 * {@link com.pdfconduit.core.operations.PdfSigner#listFields(byte[])} so a UI can render a control
 * per field and fill it. This is a read-only description — filling still happens through the
 * signer's {@code fieldValues} map keyed by {@link #name()}.
 *
 * @param name     the field's fully-qualified name (the key to fill it by)
 * @param type     a stable, UI-friendly type tag: {@code text}, {@code checkbox}, {@code radio},
 *                 {@code choice} (combo/list), {@code signature}, {@code button}, or {@code other}
 * @param value    the field's current value as a string (empty when unset)
 * @param options  selectable values for {@code choice} (combo/list options) or {@code radio}
 *                 (the on-values); empty for every other type
 * @param readOnly whether the field is marked read-only (the UI should disable its control)
 */
public record FormField(String name, String type, String value,
                        List<String> options, boolean readOnly) {}
