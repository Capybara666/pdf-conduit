package com.pdfconduit.web.dto;

import com.pdfconduit.core.analyze.PiiCategory;
import com.pdfconduit.core.analyze.PiiScanResult;
import com.pdfconduit.core.analyze.RiskLevel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregated GDPR / PII audit report for a batch of files, returned by
 * {@code POST /api/gdpr-scan-batch}. Combines a document-level roll-up (file count, total distinct
 * findings, highest risk seen, and finding counts summed per {@link PiiCategory}) with the full
 * per-file {@link PiiReportDto} so the frontend can show both an at-a-glance summary and a
 * drill-down. Only masked samples ever leave the server; nothing is stored.
 *
 * @param fileCount        how many files were scanned
 * @param totalFindings    total distinct personal-data items across all files
 * @param highestRisk      the single highest {@link RiskLevel} name seen across the batch
 * @param countsByCategory distinct-finding count per category name, summed across the batch
 * @param files            per-file reports, in upload order
 */
public record BatchPiiReportDto(
        int fileCount,
        int totalFindings,
        String highestRisk,
        Map<String, Integer> countsByCategory,
        List<FileReport> files) {

    /** One scanned file: its name and its individual {@link PiiReportDto}. */
    public record FileReport(String filename, PiiReportDto report) {}

    /**
     * Builds the aggregate from parallel filename / scan-result lists (same order, same length).
     */
    public static BatchPiiReportDto of(List<String> filenames, List<PiiScanResult> results) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<FileReport> files = new java.util.ArrayList<>(results.size());
        int totalFindings = 0;
        RiskLevel highest = RiskLevel.NONE;

        for (int i = 0; i < results.size(); i++) {
            PiiScanResult r = results.get(i);
            totalFindings += r.totalFindings();
            if (r.risk().ordinal() > highest.ordinal()) highest = r.risk();
            for (Map.Entry<PiiCategory, Integer> e : r.countsByCategory().entrySet()) {
                counts.merge(e.getKey().name(), e.getValue(), Integer::sum);
            }
            files.add(new FileReport(filenames.get(i), PiiReportDto.of(r)));
        }

        return new BatchPiiReportDto(results.size(), totalFindings, highest.name(), counts, files);
    }
}
