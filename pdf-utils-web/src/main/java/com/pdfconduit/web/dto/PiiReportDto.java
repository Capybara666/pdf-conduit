package com.pdfconduit.web.dto;

import com.pdfconduit.core.analyze.PiiCategory;
import com.pdfconduit.core.analyze.PiiFinding;
import com.pdfconduit.core.analyze.PiiScanResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON view of a {@link PiiScanResult} — the GDPR / PII scan report returned by
 * {@code POST /api/gdpr-scan}. Enum values are rendered as their names so the
 * frontend can map them to localized labels; {@code countsByCategory} is a plain
 * object keyed by {@link PiiCategory} name.
 *
 * @param totalFindings    number of distinct personal-data items found
 * @param risk             overall risk level ({@code NONE}/{@code LOW}/{@code MEDIUM}/{@code HIGH})
 * @param pagesScanned     how many pages were scanned
 * @param countsByCategory distinct-finding count per category name
 * @param findings         the distinct findings (masked samples only — never the raw value)
 */
public record PiiReportDto(
        int totalFindings,
        String risk,
        int pagesScanned,
        Map<String, Integer> countsByCategory,
        List<Finding> findings) {

    /** A single finding row (type + category as enum names, masked sample, page, occurrences). */
    public record Finding(
            String type,
            String category,
            int page,
            String maskedSample,
            int occurrences) {

        static Finding of(PiiFinding f) {
            return new Finding(
                    f.type().name(), f.category().name(), f.page(), f.maskedSample(), f.occurrences());
        }
    }

    public static PiiReportDto of(PiiScanResult r) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map.Entry<PiiCategory, Integer> e : r.countsByCategory().entrySet()) {
            counts.put(e.getKey().name(), e.getValue());
        }
        List<Finding> findings = r.findings().stream().map(Finding::of).toList();
        return new PiiReportDto(r.totalFindings(), r.risk().name(), r.pagesScanned(), counts, findings);
    }
}
