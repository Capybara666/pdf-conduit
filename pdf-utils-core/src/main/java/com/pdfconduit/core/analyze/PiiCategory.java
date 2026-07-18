package com.pdfconduit.core.analyze;

/**
 * The GDPR-relevant grouping a piece of detected personal data falls into.
 *
 * <p>Categories drive {@link RiskLevel risk scoring}: {@link #FINANCIAL},
 * {@link #NATIONAL_ID} and {@link #SPECIAL_CATEGORY} are treated as high risk,
 * while {@link #IDENTIFIER}, {@link #CONTACT} and {@link #ONLINE_IDENTIFIER} are
 * lower risk on their own.
 */
public enum PiiCategory {
    /** A generic direct identifier of a person (reserved for future detectors). */
    IDENTIFIER,
    /** Contact details — email address, phone number. */
    CONTACT,
    /** Financial data — IBAN / bank account, payment card number. */
    FINANCIAL,
    /** A government-issued national identifier — PESEL, NIP, SSN, … */
    NATIONAL_ID,
    /** An online identifier — IP address, URL carrying embedded credentials. */
    ONLINE_IDENTIFIER,
    /**
     * GDPR Article 9 "special categories" — health, religion, ethnicity/race,
     * political opinions, trade-union membership, sexual orientation, biometric
     * or genetic data. Detected via keyword context, so findings are
     * lower-confidence signals of <em>possible</em> special-category content
     * rather than validated identifiers.
     */
    SPECIAL_CATEGORY
}
