package com.pdfconduit.core.util;

/**
 * The one place a user-supplied file name is reduced to something safe to use — path stripped,
 * traversal neutralised, a sane fallback when nothing usable is left.
 *
 * <p>Four near-identical copies of this logic used to live in the web layer and in
 * {@code service/MemoryOperations} (upload naming, ZIP entry naming, output-stem naming). They
 * drifted: only one dropped leading dots, only two removed the extension — so a hardening fix
 * applied to one silently missed the others. The three methods here keep those genuinely different
 * jobs distinct and explicit, while sharing the single path-stripping implementation:
 *
 * <ul>
 *   <li>{@link #basename(String, String)} — an upload's own name (path stripped, trimmed);</li>
 *   <li>{@link #stem(String)} — the basename minus its extension, for building output names;</li>
 *   <li>{@link #sanitizeEntry(String)} — a ZIP entry name (basename with leading dots dropped),
 *       the zip-slip guard.</li>
 * </ul>
 */
public final class Filenames {

    /** Used when a name carries nothing usable ({@code null}, blank, {@code ".."}, …). */
    public static final String DEFAULT_FALLBACK = "file";

    private Filenames() {}

    /** {@link #basename(String, String)} with the {@value #DEFAULT_FALLBACK} fallback. */
    public static String basename(String name) {
        return basename(name, DEFAULT_FALLBACK);
    }

    /**
     * The name's last path segment ({@code /} and {@code \} both treated as separators), trimmed —
     * {@code fallback} when {@code name} is null/blank or nothing survives.
     */
    public static String basename(String name, String fallback) {
        if (name == null || name.isBlank()) return fallback;
        String n = lastSegment(name).strip();
        return n.isBlank() ? fallback : n;
    }

    /**
     * The basename without its extension, for composing {@code <stem><suffix>.pdf} output names.
     *
     * <p>Deliberately does <em>not</em> trim surrounding whitespace: output names have always been
     * built straight off the path-stripped name, and trimming here would silently rename outputs.
     * A leading dot is kept (a dotfile's {@code .env} stems to {@code .env}, not to nothing).
     */
    public static String stem(String name) {
        if (name == null || name.isBlank()) return DEFAULT_FALLBACK;
        String n = lastSegment(name);
        int dot = n.lastIndexOf('.');
        String stem = dot > 0 ? n.substring(0, dot) : n;
        return stem.isBlank() ? DEFAULT_FALLBACK : stem;
    }

    /**
     * A safe ZIP entry name: {@link #basename(String)} plus any leading dots dropped, so a crafted
     * upload name ({@code ../../etc/passwd}, {@code ..}) cannot write outside the archive root when
     * the ZIP is later extracted (zip-slip). Normal dotted names keep their dots.
     */
    public static String sanitizeEntry(String name) {
        String n = basename(name);
        while (n.startsWith(".")) n = n.substring(1);
        return n.isBlank() ? DEFAULT_FALLBACK : n;
    }

    private static String lastSegment(String name) {
        String n = name.replace('\\', '/');
        int slash = n.lastIndexOf('/');
        return slash >= 0 ? n.substring(slash + 1) : n;
    }
}
