package com.pdfconduit.web.dto;

/** The JSON error body returned for every failed request: a stable {@code code} + a human message. */
public record ApiError(String code, String error) {}
