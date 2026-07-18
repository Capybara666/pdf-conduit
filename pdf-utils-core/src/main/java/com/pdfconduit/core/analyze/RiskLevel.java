package com.pdfconduit.core.analyze;

/**
 * Overall privacy risk of a scanned document, derived from the categories of the
 * personal data found in it. See {@link PiiScanner} for the scoring rule.
 */
public enum RiskLevel {
    /** No personal data detected. */
    NONE,
    /** A single low-risk identifier/contact/online identifier. */
    LOW,
    /** Several low-risk identifiers, but nothing financial / national-id / special. */
    MEDIUM,
    /** Financial, national-id or GDPR special-category data present. */
    HIGH
}
