package com.pdfconduit.core.analyze;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The extensibility seam of the PII scanner: a registry of {@link Detector}s plus
 * the checksum validators and masking helpers they rely on.
 *
 * <p>Each detector is a {@code (name, type, extractor, masker)} tuple. Adding a new
 * one — for another country's national id, say — is a one-liner in
 * {@link #registry()}: supply an {@link Extractor} that returns the canonical
 * (already validated) values found in a page's text, and a masker that redacts a
 * value for display.
 *
 * <p>Precision is favoured over recall. Financial and national-id detectors reject
 * candidates that fail a checksum (Luhn, IBAN mod-97, PESEL/NIP/REGON weights), so
 * random digit runs are not reported. Phone and special-category (keyword)
 * detectors are heuristic and therefore lower-confidence context signals.
 *
 * <p>Utility class: {@code final}, private constructor, static members only.
 */
public final class PiiDetectors {

    private PiiDetectors() {}

    /** Returns the canonical (validated, normalised) values of one kind in a page's text. */
    @FunctionalInterface
    public interface Extractor {
        List<String> extract(String text);
    }

    /**
     * One detector.
     *
     * @param name      short human-readable name (for diagnostics / future catalogs)
     * @param type      the {@link PiiType} produced
     * @param extractor finds canonical values in text
     * @param masker    redacts a canonical value into a display-safe sample
     */
    public record Detector(String name, PiiType type, Extractor extractor,
                           Function<String, String> masker) {}

    // ------------------------------------------------------------------
    // Registry — the one place to add a detector.
    // ------------------------------------------------------------------

    /** The full, ordered list of active detectors. */
    public static List<Detector> registry() {
        List<Detector> d = new ArrayList<>();
        d.add(new Detector("email", PiiType.EMAIL, PiiDetectors::emails, PiiDetectors::maskEmail));
        d.add(new Detector("phone", PiiType.PHONE, PiiDetectors::phones, PiiDetectors::maskPhone));
        d.add(new Detector("ipv4", PiiType.IPV4, PiiDetectors::ipv4s, PiiDetectors::maskIpv4));
        d.add(new Detector("ipv6", PiiType.IPV6, PiiDetectors::ipv6s, PiiDetectors::maskIpv6));
        d.add(new Detector("url-credentials", PiiType.URL_CREDENTIALS,
                PiiDetectors::urlCredentials, PiiDetectors::maskUrlCredentials));
        d.add(new Detector("iban", PiiType.IBAN, PiiDetectors::ibans, PiiDetectors::maskIban));
        d.add(new Detector("credit-card", PiiType.CREDIT_CARD, PiiDetectors::cards, PiiDetectors::maskCard));
        d.add(new Detector("pesel", PiiType.PESEL, PiiDetectors::pesels, PiiDetectors::maskPesel));
        d.add(new Detector("nip", PiiType.NIP, PiiDetectors::nips, d10 -> maskTail(d10, 3)));
        d.add(new Detector("regon", PiiType.REGON, PiiDetectors::regons, d9 -> maskTail(d9, 3)));
        d.add(new Detector("us-ssn", PiiType.US_SSN, PiiDetectors::ssns, PiiDetectors::maskSsn));
        // GDPR Art. 9 special categories — keyword context (lower confidence).
        d.add(keywordDetector("health", PiiType.HEALTH, HEALTH_WORDS));
        d.add(keywordDetector("religion", PiiType.RELIGION, RELIGION_WORDS));
        d.add(keywordDetector("ethnicity", PiiType.ETHNICITY, ETHNICITY_WORDS));
        d.add(keywordDetector("political", PiiType.POLITICAL_OPINION, POLITICAL_WORDS));
        d.add(keywordDetector("trade-union", PiiType.TRADE_UNION, UNION_WORDS));
        d.add(keywordDetector("sexual-orientation", PiiType.SEXUAL_ORIENTATION, ORIENTATION_WORDS));
        d.add(keywordDetector("biometric-genetic", PiiType.BIOMETRIC_GENETIC, BIOMETRIC_WORDS));
        return d;
    }

    // ------------------------------------------------------------------
    // Checksum validators (public — unit-tested directly).
    // ------------------------------------------------------------------

    /** Luhn (mod-10) check for a 13–19 digit card number. */
    public static boolean luhnValid(String digits) {
        if (!digits.matches("\\d{13,19}")) return false;
        int sum = 0;
        boolean alt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (alt) {
                n *= 2;
                if (n > 9) n -= 9;
            }
            sum += n;
            alt = !alt;
        }
        return sum % 10 == 0;
    }

    /** IBAN mod-97 check (ISO 13616): rearrange, letters→digits, remainder must be 1. */
    public static boolean ibanValid(String iban) {
        String s = iban.replaceAll("\\s", "").toUpperCase();
        if (s.length() < 15 || s.length() > 34) return false;
        if (!s.matches("[A-Z]{2}\\d{2}[A-Z0-9]+")) return false;
        String rearranged = s.substring(4) + s.substring(0, 4);
        int rem = 0;
        for (int i = 0; i < rearranged.length(); i++) {
            char c = rearranged.charAt(i);
            int v = Character.isDigit(c) ? c - '0' : c - 'A' + 10;
            // Feed one or two decimal digits at a time, keeping a running mod-97.
            rem = v < 10 ? (rem * 10 + v) % 97 : (rem * 100 + v) % 97;
        }
        return rem == 1;
    }

    /** PESEL: 11 digits, weighted checksum, and a valid embedded birthdate. */
    public static boolean peselValid(String d) {
        if (!d.matches("\\d{11}")) return false;
        int[] w = {1, 3, 7, 9, 1, 3, 7, 9, 1, 3};
        int sum = 0;
        for (int i = 0; i < 10; i++) sum += (d.charAt(i) - '0') * w[i];
        int ctrl = (10 - (sum % 10)) % 10;
        if (ctrl != d.charAt(10) - '0') return false;
        return peselBirthdateValid(d);
    }

    private static boolean peselBirthdateValid(String d) {
        int yy = Integer.parseInt(d.substring(0, 2));
        int mm = Integer.parseInt(d.substring(2, 4));
        int dd = Integer.parseInt(d.substring(4, 6));
        int century;
        int month;
        if (mm >= 1 && mm <= 12) { century = 1900; month = mm; }
        else if (mm >= 21 && mm <= 32) { century = 2000; month = mm - 20; }
        else if (mm >= 41 && mm <= 52) { century = 2100; month = mm - 40; }
        else if (mm >= 61 && mm <= 72) { century = 2200; month = mm - 60; }
        else if (mm >= 81 && mm <= 92) { century = 1800; month = mm - 80; }
        else return false;
        try {
            LocalDate.of(century + yy, month, dd);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** NIP: 10 digits, weighted mod-11 checksum (a remainder of 10 is invalid). */
    public static boolean nipValid(String d) {
        if (!d.matches("\\d{10}") || d.equals("0000000000")) return false;
        int[] w = {6, 5, 7, 2, 3, 4, 5, 6, 7};
        int sum = 0;
        for (int i = 0; i < 9; i++) sum += (d.charAt(i) - '0') * w[i];
        int ctrl = sum % 11;
        return ctrl != 10 && ctrl == d.charAt(9) - '0';
    }

    /** REGON: 9- or 14-digit weighted mod-11 checksum. */
    public static boolean regonValid(String d) {
        if (d.matches("\\d{9}")) {
            return regonCheck(d, new int[]{8, 9, 2, 3, 4, 5, 6, 7});
        }
        if (d.matches("\\d{14}")) {
            return regonCheck(d, new int[]{2, 4, 8, 5, 0, 9, 7, 3, 6, 1, 2, 4, 8});
        }
        return false;
    }

    private static boolean regonCheck(String d, int[] w) {
        int sum = 0;
        for (int i = 0; i < w.length; i++) sum += (d.charAt(i) - '0') * w[i];
        int c = sum % 11;
        if (c == 10) c = 0;
        return c == d.charAt(w.length) - '0';
    }

    /** US SSN plausibility for a {@code AAA-GG-SSSS} string (rules from SSA). */
    public static boolean ssnPlausible(String ssn) {
        String[] p = ssn.split("-");
        if (p.length != 3) return false;
        int area = Integer.parseInt(p[0]);
        int group = Integer.parseInt(p[1]);
        int serial = Integer.parseInt(p[2]);
        if (area == 0 || area == 666 || area >= 900) return false;
        return group != 0 && serial != 0;
    }

    // ------------------------------------------------------------------
    // Extractors.
    // ------------------------------------------------------------------

    private static final Pattern EMAIL = Pattern.compile(
            "(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}(?![A-Za-z0-9.-])");

    private static List<String> emails(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = EMAIL.matcher(text);
        while (m.find()) out.add(m.group());
        return out;
    }

    // Candidate: starts on a digit or '+', run of digits and phone separators.
    private static final Pattern PHONE = Pattern.compile(
            "(?<![\\w+])(\\+?\\d[\\d\\s().-]{6,}\\d)(?!\\d)");
    private static final Pattern SSN = Pattern.compile("\\d{3}-\\d{2}-\\d{4}");

    private static List<String> phones(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = PHONE.matcher(text);
        while (m.find()) {
            String s = m.group(1);
            String digits = s.replaceAll("\\D", "");
            if (digits.length() < 9 || digits.length() > 15) continue;
            boolean hasPlus = s.trim().startsWith("+");
            boolean hasSeparator = s.replaceAll("[\\d+]", "").length() > 0;
            if (!hasPlus && !hasSeparator) continue;         // bare digit runs aren't phones
            if (isIpv4(s.trim())) continue;                   // an IP, not a phone
            if (SSN.matcher(s).matches()) continue;           // an SSN, not a phone
            if (luhnValid(digits)) continue;                  // a card, not a phone
            out.add((hasPlus ? "+" : "") + digits);
        }
        return out;
    }

    private static final Pattern IPV4 = Pattern.compile(
            "(?<![\\d.])((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(?![\\d.])");

    private static boolean isIpv4(String s) {
        return IPV4.matcher(s).matches();
    }

    private static List<String> ipv4s(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = IPV4.matcher(text);
        while (m.find()) out.add(m.group());
        return out;
    }

    // Candidate token of hex + colons; validated structurally in isIpv6.
    private static final Pattern IPV6_CANDIDATE = Pattern.compile(
            "(?<![0-9A-Za-z:._-])[0-9A-Fa-f:]{2,45}(?![0-9A-Za-z:._-])");

    private static List<String> ipv6s(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = IPV6_CANDIDATE.matcher(text);
        while (m.find()) {
            String t = m.group();
            if (isIpv6(t)) out.add(t.toLowerCase());
        }
        return out;
    }

    /** Structural IPv6 validation (full 8-group form or a single {@code ::} compression). */
    static boolean isIpv6(String s) {
        if (s.indexOf(':') < 0) return false;
        int dbl = s.indexOf("::");
        if (dbl >= 0) {
            if (s.indexOf("::", dbl + 1) >= 0) return false;   // at most one "::"
            String head = s.substring(0, dbl);
            String tail = s.substring(dbl + 2);
            List<String> groups = new ArrayList<>();
            if (!head.isEmpty()) { for (String g : head.split(":", -1)) groups.add(g); }
            if (!tail.isEmpty()) { for (String g : tail.split(":", -1)) groups.add(g); }
            if (groups.size() > 7) return false;               // "::" must compress ≥1 group
            return groups.stream().allMatch(PiiDetectors::hexGroup);
        }
        String[] groups = s.split(":", -1);
        if (groups.length != 8) return false;
        for (String g : groups) if (!hexGroup(g)) return false;
        return true;
    }

    private static boolean hexGroup(String g) {
        return g.matches("[0-9A-Fa-f]{1,4}");
    }

    private static final Pattern URL_CRED = Pattern.compile(
            "(?<![\\w])(https?|ftp)://([^\\s/:@]+):([^\\s/@]+)@([^\\s/]+)([^\\s]*)");

    private static List<String> urlCredentials(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = URL_CRED.matcher(text);
        while (m.find()) out.add(m.group());
        return out;
    }

    // Grouping allows single spaces/tabs (printed IBANs are grouped in fours) but not
    // newlines, so a candidate never spills onto the next line.
    private static final Pattern IBAN_CANDIDATE = Pattern.compile(
            "(?<![A-Za-z0-9])([A-Z]{2}\\d{2}(?:[ \\t]?[A-Za-z0-9]){11,30})(?![A-Za-z0-9])");

    private static List<String> ibans(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = IBAN_CANDIDATE.matcher(text);
        while (m.find()) {
            // A greedy candidate may have absorbed a trailing word; accumulate the
            // space-separated tokens and emit whenever the prefix validates.
            StringBuilder acc = new StringBuilder();
            for (String part : m.group(1).split("[ \\t]+")) {
                acc.append(part);
                String canonical = acc.toString().toUpperCase();
                if (ibanValid(canonical)) {
                    out.add(canonical);
                    acc.setLength(0);
                }
            }
        }
        return out;
    }

    // 13–19 digits, optionally grouped by single spaces or dashes.
    private static final Pattern CARD_CANDIDATE = Pattern.compile(
            "(?<![\\d.])\\d(?:[ -]?\\d){12,18}(?![\\d.])");

    private static List<String> cards(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = CARD_CANDIDATE.matcher(text);
        while (m.find()) {
            String digits = m.group().replaceAll("\\D", "");
            if (luhnValid(digits)) out.add(digits);
        }
        return out;
    }

    private static final Pattern DIGITS_11 = Pattern.compile("(?<![\\d])\\d{11}(?![\\d])");
    private static final Pattern DIGITS_10 = Pattern.compile("(?<![\\d-])\\d{10}(?![\\d-])");
    private static final Pattern DIGITS_9_14 = Pattern.compile("(?<![\\d])(\\d{9}|\\d{14})(?![\\d])");

    private static List<String> pesels(String text) {
        return validatedRuns(text, DIGITS_11, PiiDetectors::peselValid);
    }

    private static List<String> nips(String text) {
        return validatedRuns(text, DIGITS_10, PiiDetectors::nipValid);
    }

    private static List<String> regons(String text) {
        return validatedRuns(text, DIGITS_9_14, PiiDetectors::regonValid);
    }

    private static List<String> validatedRuns(String text, Pattern p,
                                              Function<String, Boolean> valid) {
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(text);
        while (m.find()) {
            String d = m.group();
            if (valid.apply(d)) out.add(d);
        }
        return out;
    }

    private static final Pattern SSN_CANDIDATE = Pattern.compile("(?<![\\d-])\\d{3}-\\d{2}-\\d{4}(?![\\d-])");

    private static List<String> ssns(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = SSN_CANDIDATE.matcher(text);
        while (m.find()) {
            String s = m.group();
            if (ssnPlausible(s)) out.add(s);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Special-category keyword detectors (lower confidence).
    // ------------------------------------------------------------------

    private static final List<String> HEALTH_WORDS = List.of(
            "diagnosis", "disease", "illness", "patient", "prescription", "medication",
            "cancer", "diabetes", "HIV", "pregnancy", "disability", "psychiatric",
            "chemotherapy", "medical record", "mental health",
            "diagnoza", "choroba", "pacjent", "ciąża", "niepełnosprawność", "lekarz");

    private static final List<String> RELIGION_WORDS = List.of(
            "religion", "religious", "christian", "muslim", "catholic", "jewish",
            "buddhist", "hindu", "atheist", "church", "mosque", "synagogue",
            "religia", "wyznanie", "katolik", "kościół");

    private static final List<String> ETHNICITY_WORDS = List.of(
            "ethnicity", "ethnic origin", "ethnic background", "race", "racial", "roma",
            "pochodzenie etniczne", "rasa", "narodowość");

    private static final List<String> POLITICAL_WORDS = List.of(
            "political opinion", "political party", "political affiliation",
            "communist", "socialist", "conservative party", "left-wing", "right-wing",
            "poglądy polityczne", "partia polityczna");

    private static final List<String> UNION_WORDS = List.of(
            "trade union", "labor union", "labour union", "union member", "union membership",
            "związek zawodowy");

    private static final List<String> ORIENTATION_WORDS = List.of(
            "sexual orientation", "homosexual", "heterosexual", "bisexual",
            "lesbian", "LGBT", "gay",
            "orientacja seksualna");

    private static final List<String> BIOMETRIC_WORDS = List.of(
            "biometric", "fingerprint", "facial recognition", "iris scan", "retina scan",
            "genetic data", "DNA sample",
            "biometryczny", "odcisk palca", "dane genetyczne");

    private static Detector keywordDetector(String name, PiiType type, List<String> words) {
        StringBuilder alt = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            if (i > 0) alt.append('|');
            alt.append(Pattern.quote(words.get(i)));
        }
        // Unicode-aware boundaries so Polish diacritics are handled correctly.
        Pattern p = Pattern.compile(
                "(?<![\\p{L}\\p{N}])(" + alt + ")(?![\\p{L}\\p{N}])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
        Extractor extractor = text -> {
            List<String> out = new ArrayList<>();
            Matcher m = p.matcher(text);
            while (m.find()) out.add(m.group(1).toLowerCase());
            return out;
        };
        // The keyword itself is the "match"; it is a context signal, not a personal
        // datum, so the masked sample simply echoes the term that triggered it.
        return new Detector(name, type, extractor, kw -> kw);
    }

    // ------------------------------------------------------------------
    // Maskers — never reveal the full value for identifier-style data.
    // ------------------------------------------------------------------

    private static final char DOT = '•'; // •

    private static String bullets(int n) {
        return String.valueOf(DOT).repeat(Math.max(0, n));
    }

    private static String firstChar(String s) {
        return s.isEmpty() ? "" : s.substring(0, 1);
    }

    static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at < 0) return bullets(email.length());
        String local = email.substring(0, at);
        String domain = email.substring(at + 1);
        int dot = domain.lastIndexOf('.');
        String host = dot > 0 ? domain.substring(0, dot) : domain;
        String tld = dot > 0 ? domain.substring(dot) : "";
        return firstChar(local) + bullets(3) + "@" + firstChar(host) + bullets(3) + tld;
    }

    static String maskPhone(String canonical) {
        boolean plus = canonical.startsWith("+");
        String digits = canonical.replaceAll("\\D", "");
        String last2 = digits.length() >= 2 ? digits.substring(digits.length() - 2) : digits;
        return (plus ? "+" : "") + bullets(digits.length() - last2.length()) + last2;
    }

    static String maskIpv4(String ip) {
        String[] o = ip.split("\\.");
        return bullets(1) + "." + bullets(1) + "." + bullets(1) + "." + o[3];
    }

    static String maskIpv6(String ip) {
        int c = ip.lastIndexOf(':');
        return bullets(4) + ":…:" + ip.substring(c + 1);
    }

    static String maskUrlCredentials(String url) {
        // scheme://user:password@host…  →  scheme://u•••:•••@host…
        Matcher m = URL_CRED.matcher(url);
        if (!m.matches()) return bullets(8);
        return m.group(1) + "://" + firstChar(m.group(2)) + bullets(3) + ":" + bullets(3)
                + "@" + m.group(4) + m.group(5);
    }

    static String maskIban(String iban) {
        String last4 = iban.substring(iban.length() - 4);
        String cc = iban.substring(0, 2);
        return group4(cc + bullets(iban.length() - 6) + last4);
    }

    static String maskCard(String digits) {
        String last4 = digits.substring(digits.length() - 4);
        return group4(bullets(digits.length() - 4) + last4);
    }

    static String maskPesel(String d) {
        // Mask everything but the leading digits (year), enough to recognise the type.
        return d.substring(0, 3) + bullets(d.length() - 3);
    }

    static String maskTail(String digits, int keep) {
        int k = Math.min(keep, digits.length());
        return bullets(digits.length() - k) + digits.substring(digits.length() - k);
    }

    static String maskSsn(String ssn) {
        String last4 = ssn.substring(ssn.length() - 4);
        return bullets(3) + "-" + bullets(2) + "-" + last4;
    }

    /** Group a masked/plain string into blocks of four separated by spaces. */
    private static String group4(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && i % 4 == 0) b.append(' ');
            b.append(s.charAt(i));
        }
        return b.toString();
    }
}
