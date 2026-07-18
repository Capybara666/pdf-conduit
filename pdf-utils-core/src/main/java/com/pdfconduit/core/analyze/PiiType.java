package com.pdfconduit.core.analyze;

/**
 * The concrete kind of personal data a detector looks for. Each type belongs to a
 * single {@link PiiCategory}, which is what drives risk scoring and reporting.
 *
 * <p>The checksum-validated types (IBAN, credit card, PESEL, NIP, REGON) are
 * high-precision; {@link #PHONE} and the special-category types are heuristic
 * (format / keyword based) and therefore lower-confidence context signals.
 */
public enum PiiType {
    EMAIL(PiiCategory.CONTACT),
    PHONE(PiiCategory.CONTACT),

    IPV4(PiiCategory.ONLINE_IDENTIFIER),
    IPV6(PiiCategory.ONLINE_IDENTIFIER),
    /** A URL with embedded {@code user:password@} credentials. */
    URL_CREDENTIALS(PiiCategory.ONLINE_IDENTIFIER),

    /** International Bank Account Number (mod-97 validated). */
    IBAN(PiiCategory.FINANCIAL),
    /** Payment card number (Luhn validated). */
    CREDIT_CARD(PiiCategory.FINANCIAL),

    /** Polish national identification number (checksum + birthdate validated). */
    PESEL(PiiCategory.NATIONAL_ID),
    /** Polish tax identification number (checksum validated). */
    NIP(PiiCategory.NATIONAL_ID),
    /** Polish business registry number (checksum validated). */
    REGON(PiiCategory.NATIONAL_ID),
    /** United States Social Security Number (format + plausibility). */
    US_SSN(PiiCategory.NATIONAL_ID),

    HEALTH(PiiCategory.SPECIAL_CATEGORY),
    RELIGION(PiiCategory.SPECIAL_CATEGORY),
    ETHNICITY(PiiCategory.SPECIAL_CATEGORY),
    POLITICAL_OPINION(PiiCategory.SPECIAL_CATEGORY),
    TRADE_UNION(PiiCategory.SPECIAL_CATEGORY),
    SEXUAL_ORIENTATION(PiiCategory.SPECIAL_CATEGORY),
    BIOMETRIC_GENETIC(PiiCategory.SPECIAL_CATEGORY);

    private final PiiCategory category;

    PiiType(PiiCategory category) {
        this.category = category;
    }

    /** The GDPR category this type of data belongs to. */
    public PiiCategory category() {
        return category;
    }
}
