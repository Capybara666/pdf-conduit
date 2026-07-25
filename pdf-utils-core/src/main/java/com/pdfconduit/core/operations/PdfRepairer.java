package com.pdfconduit.core.operations;

import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.exception.PdfUnrecoverableException;
import com.pdfconduit.core.model.RepairBytesResult;
import com.pdfconduit.core.model.RepairFinding;
import com.pdfconduit.core.model.RepairOptions;
import com.pdfconduit.core.model.RepairResult;
import com.pdfconduit.core.util.OutputPaths;
import com.pdfconduit.core.util.PdfLoader;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Tries to repair a damaged PDF — <em>not every file can be recovered</em>.
 *
 * <p>What it actually does, in order:
 * <ol>
 *   <li><b>Inspect the raw bytes</b> for concrete structural defects that a reader trips over: a
 *       missing {@code %PDF-} header, junk before it, a missing {@code %%EOF} marker, a missing
 *       {@code startxref}, or a {@code startxref} offset that points nowhere. Each becomes a
 *       {@link RepairFinding}.</li>
 *   <li><b>Probe with a strict parse</b> ({@link PDFParser#parse(boolean) parse(false)}, PDFBox's
 *       non-lenient mode). Strict mode refuses to guess: a broken cross-reference table, a bad
 *       object offset or a missing trailer root throws instead of being silently worked around. A
 *       failure here is the ground truth for "this file is damaged".</li>
 *   <li><b>Load leniently</b> — PDFBox's default mode, which falls back to scanning the whole file
 *       for {@code N G obj} markers and rebuilding the cross-reference data and trailer from what it
 *       finds.</li>
 *   <li><b>Rewrite the document in full.</b> Saving a fully-parsed {@code PDDocument} is not an
 *       incremental update: PDFBox walks the object graph from the trailer and writes a brand-new
 *       file with a fresh, correct cross-reference table. Objects nothing references — including the
 *       wreckage of the broken structure — are simply not written.</li>
 *   <li><b>Verify the output</b> with the same strict parse. Only a damaged input whose rebuild
 *       parses strictly is reported as {@code recovered}.</li>
 * </ol>
 *
 * <p><b>What repair cannot do:</b> it recovers <em>structure</em>, not content. Bytes that are gone
 * are gone — a truncated file loses the objects that were cut off, a page whose content stream is
 * corrupt stays corrupt, and damaged embedded images/fonts are copied through as they are. It also
 * does not decrypt: a password-protected file is reported as such, not cracked. When too little
 * survives to assemble a document with readable pages, the operation fails with a
 * {@link PdfUnrecoverableException} rather than emitting a plausible-looking empty PDF.
 *
 * <p>Repair is the one operation that must see its input <em>exactly</em> as supplied — the damage
 * lives in the byte structure, so callers must not pre-convert or re-encode it.
 *
 * <p>Stateless and thread-safe; both a {@code Path}-based and an in-memory {@code byte[]} variant
 * are provided.
 */
public final class PdfRepairer {

    /** How far into the file a {@code %PDF-} header may hide before we call it missing. */
    private static final int HEADER_LOOKUP_RANGE = 1024;

    /** How far back from the end we look for {@code %%EOF} (PDFBox's own default lookup range). */
    private static final int EOF_LOOKUP_RANGE = 2048;

    private static final byte[] HEADER = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] EOF = "%%EOF".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] STARTXREF = "startxref".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] XREF = "xref".getBytes(StandardCharsets.US_ASCII);

    private static final String UNRECOVERABLE =
        "This file could not be repaired: too little PDF structure survived to rebuild a document. "
        + "Repair can rebuild a damaged file's structure, but it cannot recreate data that is gone.";

    private PdfRepairer() {}

    /** Rebuilds {@code opts.input()} into {@code opts.output()}, reporting what was actually wrong. */
    public static RepairResult execute(RepairOptions opts) throws PdfOperationException {
        byte[] input;
        try {
            input = Files.readAllBytes(opts.input());
        } catch (IOException e) {
            throw new PdfOperationException(
                "Cannot read " + opts.input().getFileName() + ": " + e.getMessage(), e);
        }
        RepairBytesResult r = executeBytes(input);
        try {
            OutputPaths.ensureParentDir(opts.output());
            Files.write(opts.output(), r.bytes());
        } catch (IOException e) {
            throw new PdfOperationException("Repair failed: " + e.getMessage(), e);
        }
        return new RepairResult(opts.output(), r.wasDamaged(), r.recovered(), r.pageCount(),
            r.originalBytes(), r.resultBytes(), r.findings());
    }

    /** In-memory variant: rebuild {@code pdf} and report what was actually wrong with it. */
    public static RepairBytesResult executeBytes(byte[] pdf) throws PdfOperationException {
        if (pdf == null || pdf.length == 0) {
            throw new PdfUnrecoverableException(UNRECOVERABLE);
        }

        Set<RepairFinding> findings = EnumSet.noneOf(RepairFinding.class);
        findings.addAll(structuralDefects(pdf));

        // Ground truth for "damaged": strict mode refuses to guess around broken structure.
        boolean strictOk = parsesStrictly(() -> new RandomAccessReadBuffer(pdf));
        if (!strictOk) findings.add(RepairFinding.XREF_REBUILT);
        boolean wasDamaged = !strictOk || !findings.isEmpty();

        byte[] out;
        int pageCount;
        try (PDDocument doc = lenientLoad(pdf)) {
            pageCount = doc.getNumberOfPages();
            if (pageCount == 0) throw new PdfUnrecoverableException(UNRECOVERABLE);
            // Touch every page so a page tree that only *looks* recovered fails here, loudly.
            for (PDPage page : doc.getPages()) page.getMediaBox();
            // A non-incremental save = full object rewrite: fresh xref, unreachable wreckage dropped.
            out = PdfLoader.toBytes(doc);
        } catch (PdfOperationException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new PdfUnrecoverableException(
                "This file could not be repaired: the surviving structure could not be rebuilt into "
                + "a valid PDF.", e);
        }

        // Verified, not assumed: a damaged input counts as recovered only if the rebuild is sound.
        boolean recovered = false;
        if (wasDamaged) {
            byte[] rebuilt = out;
            recovered = parsesStrictly(() -> new RandomAccessReadBuffer(rebuilt));
            if (!recovered) findings.add(RepairFinding.REBUILD_INCOMPLETE);
        }
        return new RepairBytesResult(out, wasDamaged, recovered, pageCount,
            pdf.length, out.length, ordered(findings));
    }

    // --- internals --------------------------------------------------------

    /**
     * Loads through {@link PdfLoader} (PDFBox's lenient default, i.e. brute-force recovery) so the
     * password-protected message stays identical to every other surface, while a genuinely
     * unreadable file becomes the repair-specific {@link PdfUnrecoverableException}.
     */
    private static PDDocument lenientLoad(byte[] pdf) throws PdfOperationException {
        try {
            return PdfLoader.load(pdf);
        } catch (PdfOperationException e) {
            if (e.getCause() instanceof InvalidPasswordException) throw e;
            throw new PdfUnrecoverableException(UNRECOVERABLE, e);
        }
    }

    /** Opens a {@link RandomAccessRead} over the bytes/file being probed. */
    @FunctionalInterface
    private interface SourceOpener {
        RandomAccessRead open() throws IOException;
    }

    /**
     * True when the data parses under PDFBox's <em>strict</em> (non-lenient) rules and yields at
     * least one reachable page. Strict mode throws on a missing header, an unreadable cross-reference
     * table, a bad object offset or a missing trailer root instead of reconstructing them, which is
     * exactly the distinction we need. Any failure — checked or unchecked — means "not sound".
     */
    private static boolean parsesStrictly(SourceOpener source) {
        try (RandomAccessRead in = source.open()) {
            PDFParser parser = new PDFParser(in);
            try (PDDocument doc = parser.parse(false)) {
                if (doc.getNumberOfPages() == 0) return false;
                for (PDPage page : doc.getPages()) page.getMediaBox();
                return true;
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Concrete defects visible in the raw bytes, before any parser is involved: the header, the
     * end-of-file marker and the {@code startxref} pointer. These are the failures that make a
     * reader give up on an otherwise-intact file, and they are what the rewrite fixes.
     */
    private static Set<RepairFinding> structuralDefects(byte[] pdf) {
        Set<RepairFinding> found = EnumSet.noneOf(RepairFinding.class);

        int header = indexOf(pdf, HEADER, 0, Math.min(pdf.length, HEADER_LOOKUP_RANGE));
        if (header < 0) {
            found.add(RepairFinding.HEADER_MISSING);
        } else if (header > 0) {
            found.add(RepairFinding.HEADER_OFFSET);
        }

        int tailFrom = Math.max(0, pdf.length - EOF_LOOKUP_RANGE);
        if (lastIndexOf(pdf, EOF, tailFrom, pdf.length) < 0) {
            found.add(RepairFinding.EOF_MISSING);
        }

        int sx = lastIndexOf(pdf, STARTXREF, 0, pdf.length);
        if (sx < 0) {
            found.add(RepairFinding.STARTXREF_MISSING);
        } else {
            long offset = readOffset(pdf, sx + STARTXREF.length);
            // A file with leading junk keeps offsets relative to the header, so try both anchors.
            boolean ok = pointsAtXrefOrObject(pdf, offset)
                || (header > 0 && pointsAtXrefOrObject(pdf, offset + header));
            if (!ok) found.add(RepairFinding.STARTXREF_INVALID);
        }
        return found;
    }

    /** Reads the decimal offset that follows {@code startxref}; -1 when there is no number. */
    private static long readOffset(byte[] pdf, int from) {
        int i = from;
        while (i < pdf.length && isWhitespace(pdf[i])) i++;
        long value = -1;
        while (i < pdf.length && pdf[i] >= '0' && pdf[i] <= '9') {
            if (value < 0) value = 0;
            value = value * 10 + (pdf[i] - '0');
            if (value > Integer.MAX_VALUE) return -1;   // absurd offset: treat as invalid
            i++;
        }
        return value;
    }

    /** True when {@code offset} lands on an {@code xref} table or on an {@code N G obj} header. */
    private static boolean pointsAtXrefOrObject(byte[] pdf, long offset) {
        if (offset < 0 || offset >= pdf.length) return false;
        int at = (int) offset;
        if (startsWith(pdf, XREF, at)) return true;
        // Cross-reference *stream*: the offset points at "<num> <gen> obj".
        int i = at;
        int digits = 0;
        while (i < pdf.length && pdf[i] >= '0' && pdf[i] <= '9') { i++; digits++; }
        if (digits == 0) return false;
        int spaces = 0;
        while (i < pdf.length && isWhitespace(pdf[i])) { i++; spaces++; }
        if (spaces == 0) return false;
        digits = 0;
        while (i < pdf.length && pdf[i] >= '0' && pdf[i] <= '9') { i++; digits++; }
        if (digits == 0) return false;
        while (i < pdf.length && isWhitespace(pdf[i])) i++;
        return startsWith(pdf, "obj".getBytes(StandardCharsets.US_ASCII), i);
    }

    private static boolean isWhitespace(byte b) {
        return b == ' ' || b == '\r' || b == '\n' || b == '\t' || b == 0 || b == '\f';
    }

    private static boolean startsWith(byte[] data, byte[] needle, int at) {
        if (at < 0 || at + needle.length > data.length) return false;
        for (int i = 0; i < needle.length; i++) {
            if (data[at + i] != needle[i]) return false;
        }
        return true;
    }

    private static int indexOf(byte[] data, byte[] needle, int from, int to) {
        for (int i = Math.max(0, from); i <= to - needle.length; i++) {
            if (startsWith(data, needle, i)) return i;
        }
        return -1;
    }

    private static int lastIndexOf(byte[] data, byte[] needle, int from, int to) {
        for (int i = Math.min(to, data.length) - needle.length; i >= from; i--) {
            if (startsWith(data, needle, i)) return i;
        }
        return -1;
    }

    /** Findings in declaration order, so reports read the same way every time. */
    private static List<RepairFinding> ordered(Set<RepairFinding> findings) {
        List<RepairFinding> out = new ArrayList<>(findings.size());
        for (RepairFinding f : RepairFinding.values()) {
            if (findings.contains(f)) out.add(f);
        }
        return out;
    }

    /**
     * Strict-parse probe for a file on disk — the {@code Path} analog of the byte[] probe, used by
     * callers (tests, CLI) that want to check a file without loading it into memory twice.
     */
    public static boolean isStructurallySound(Path pdf) {
        return parsesStrictly(() -> new RandomAccessReadBufferedFile(pdf.toFile()));
    }
}
