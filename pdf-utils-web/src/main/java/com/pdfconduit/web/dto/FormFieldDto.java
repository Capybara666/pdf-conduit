package com.pdfconduit.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pdfconduit.core.model.FormField;

import java.util.List;

/**
 * JSON view of one enumerated AcroForm field from {@code POST /api/form-fields}. Mirrors the core
 * {@link FormField}; {@code options} is omitted when empty (only choice/radio fields carry options),
 * so the wire shape is {@code {name, type, value, options?, readOnly}}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FormFieldDto(String name, String type, String value,
                           List<String> options, boolean readOnly) {

    public static FormFieldDto of(FormField f) {
        List<String> options = (f.options() == null || f.options().isEmpty()) ? null : f.options();
        return new FormFieldDto(f.name(), f.type(), f.value(), options, f.readOnly());
    }
}
