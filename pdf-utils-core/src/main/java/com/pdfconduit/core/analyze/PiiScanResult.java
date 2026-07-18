package com.pdfconduit.core.analyze;

import java.util.List;
import java.util.Map;

/**
 * The outcome of a {@link PiiScanner} run.
 *
 * @param totalFindings    number of distinct personal-data items found
 *                         ({@code == findings.size()})
 * @param risk             overall {@link RiskLevel} for the document
 * @param findings         the distinct findings, in the order first encountered
 * @param countsByCategory number of distinct findings per {@link PiiCategory}
 * @param pagesScanned     how many pages were scanned
 */
public record PiiScanResult(
        int totalFindings,
        RiskLevel risk,
        List<PiiFinding> findings,
        Map<PiiCategory, Integer> countsByCategory,
        int pagesScanned) {

    public PiiScanResult {
        findings = List.copyOf(findings);
        countsByCategory = Map.copyOf(countsByCategory);
    }
}
