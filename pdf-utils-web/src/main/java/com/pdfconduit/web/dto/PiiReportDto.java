package com.pdfconduit.web.dto;

import com.pdfconduit.core.analyze.PiiCategory;
import com.pdfconduit.core.analyze.PiiFinding;
import com.pdfconduit.core.analyze.PiiRegion;
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

    /**
     * A single finding row (type + category as enum names, masked sample, page, occurrences)
     * plus the on-page {@code regions} of every occurrence. Each region matches the redact
     * tool's region shape ({@code pageIndex, x, y, width, height} in displayed-page points,
     * top-left origin, 0-based page), so the frontend can feed them straight into Redact.
     * {@code regions} is empty for special-category keyword findings.
     */
    public record Finding(
            String type,
            String category,
            int page,
            String maskedSample,
            int occurrences,
            List<Region> regions) {

        static Finding of(PiiFinding f) {
            List<Region> regions = f.regions().stream().map(Region::of).toList();
            return new Finding(
                    f.type().name(), f.category().name(), f.page(), f.maskedSample(),
                    f.occurrences(), regions);
        }
    }

    /**
     * A redaction rectangle for one occurrence, mirroring
     * {@link com.pdfconduit.web.dto.RedactRegionDto}: {@code pageIndex} is 0-based and the
     * coordinates are displayed-page points with a top-left origin.
     */
    public record Region(int pageIndex, double x, double y, double width, double height) {

        static Region of(PiiRegion r) {
            return new Region(r.page(), r.x(), r.y(), r.width(), r.height());
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
