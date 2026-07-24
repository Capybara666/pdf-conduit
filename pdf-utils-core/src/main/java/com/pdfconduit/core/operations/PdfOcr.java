package com.pdfconduit.core.operations;

import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.OcrOptions;
import com.pdfconduit.core.model.OcrResult;
import com.pdfconduit.core.util.OutputPaths;
import com.pdfconduit.core.util.PdfLoader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.util.Matrix;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Turns a (typically scanned / image-only) PDF into a <b>searchable</b> PDF by shelling out to
 * the {@code tesseract} OCR binary and drawing an <b>invisible</b> text layer over each original
 * page. The visual output is byte-for-byte the same image the user scanned; the added text is
 * rendered in {@link RenderingMode#NEITHER} (PDF text render mode 3, "invisible"), so it never
 * shows but is fully selectable and extractable — the standard way OCR tools make scans searchable.
 *
 * <p>For every page we:
 * <ol>
 *   <li>render it to a raster image at {@link #DEFAULT_DPI} (rotation applied by PDFBox);</li>
 *   <li>run {@code tesseract &lt;image&gt; stdout tsv} to get per-word bounding boxes + text;</li>
 *   <li>map each word's top-left pixel box back to PDF points (÷ dpi/72 scale, Y flipped, page
 *       rotation honoured via a single content-stream transform) and place the word there in an
 *       embedded Unicode font ({@code DejaVuSans}) sized so its rendered width ≈ the box width, so
 *       selection aligns with the glyphs underneath.</li>
 * </ol>
 *
 * <p>The {@code tesseract} binary is discovered lazily (PATH + common install locations), mirroring
 * {@link com.pdfconduit.core.convert.DocumentConverter}'s LibreOffice discovery. If it is absent,
 * {@link #execute}/{@link #executeBytes} throw a clear {@link PdfOperationException}; call
 * {@link #available()} first to gate the feature. Stateless and safe to call concurrently (each
 * call uses its own temp dir); callers that need a concurrency/timeout ceiling should wrap it.
 */
public final class PdfOcr {

    private PdfOcr() {}

    /** Render DPI used to rasterise pages for recognition when the caller does not specify one. */
    public static final int DEFAULT_DPI = 300;

    /** Absolute clamp on the render DPI (shared with the image exporter's hard ceiling). */
    public static final int MAX_DPI = PdfToImageConverter.MAX_RENDER_DPI;

    /** Default tesseract language(s) when none is supplied. */
    public static final String DEFAULT_LANGUAGES = "eng";

    /**
     * Accepted shape for the tesseract {@code -l} value: one or more language codes joined by
     * {@code +} (e.g. {@code eng}, {@code eng+pol}, {@code chi_sim}). Passed as an arg-vector element
     * (no shell), so this is hardening, not an injection fix — it rejects stray metacharacters and
     * absurd lengths before they reach the process.
     */
    private static final java.util.regex.Pattern LANG_PATTERN =
        java.util.regex.Pattern.compile("^[A-Za-z0-9+_-]{1,64}$");

    /** Bundled Unicode TTF (Bitstream Vera / DejaVu, permissive) — encodes arbitrary Unicode words. */
    private static final String FONT_RESOURCE = "/fonts/DejaVuSans.ttf";

    // --- tesseract binary discovery (mirrors DocumentConverter's soffice lookup) ---

    private static final String[] TESSERACT_CANDIDATES = {
        "tesseract",
        "/usr/bin/tesseract", "/usr/local/bin/tesseract", "/opt/homebrew/bin/tesseract",
        "/opt/local/bin/tesseract", "/snap/bin/tesseract",
        "C:\\Program Files\\Tesseract-OCR\\tesseract.exe",
        "C:\\Program Files (x86)\\Tesseract-OCR\\tesseract.exe"
    };

    private static volatile boolean tesseractSearched;
    private static volatile String tesseractPath;
    private static volatile String tesseractOverride;   // user-supplied path, tried first
    private static volatile List<String> installedLanguages;   // cached `--list-langs` result

    /** Hard wall-clock timeout for a single {@code tesseract} invocation (per page). */
    private static final int DEFAULT_TIMEOUT_SECONDS = 120;
    private static volatile int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;

    /**
     * Sets a user-supplied {@code tesseract} path, tried before the built-in candidates. Blank
     * clears it. Resets the cached search so the change takes effect immediately.
     */
    public static void setTesseractOverride(String path) {
        synchronized (PdfOcr.class) {
            tesseractOverride = (path == null || path.isBlank()) ? null : path.strip();
            tesseractSearched = false;
            tesseractPath = null;
            installedLanguages = null;
        }
    }

    /** Sets the per-page {@code tesseract} wall-clock timeout (seconds); values &lt; 1 clamp to 1. */
    public static void setTimeoutSeconds(int seconds) {
        timeoutSeconds = Math.max(1, seconds);
    }

    /** Whether OCR is possible on this machine (the {@code tesseract} binary is resolvable). */
    public static boolean available() {
        return findTesseract() != null;
    }

    /** The resolved {@code tesseract} executable (override or auto-detected), or {@code null}. */
    public static String tesseractPath() {
        return findTesseract();
    }

    /**
     * The Tesseract language codes installed on this machine (e.g. {@code eng}, {@code pol},
     * {@code chi_sim}), sorted alphabetically. Discovered lazily — a single
     * {@code tesseract --list-langs} run — and cached for the lifetime of the process (reset by
     * {@link #setTesseractOverride}); never spawns a process per call after the first. Returns an
     * empty list when the binary is absent or listing fails, so callers can gate UI on it safely.
     * The pseudo-language {@code osd} (orientation/script detection) is excluded.
     */
    public static List<String> installedLanguages() {
        List<String> cached = installedLanguages;
        if (cached != null) return cached;
        synchronized (PdfOcr.class) {
            if (installedLanguages != null) return installedLanguages;
            installedLanguages = discoverLanguages();
            return installedLanguages;
        }
    }

    private static List<String> discoverLanguages() {
        String tesseract = findTesseract();
        if (tesseract == null) return List.of();
        try {
            // Older tesseract versions print the list on stderr — merge the streams.
            Process p = new ProcessBuilder(tesseract, "--list-langs")
                .redirectErrorStream(true).start();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return List.of();
            }
            if (p.exitValue() != 0) return List.of();
            return parseLangList(new String(out, java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    /**
     * Parses {@code tesseract --list-langs} output: a header line ("List of available languages
     * (N):") followed by one language code per line. Keeps only plausible codes (letters/digits/
     * underscores, e.g. {@code eng}, {@code chi_sim}), drops the {@code osd} pseudo-language, and
     * returns them sorted + de-duplicated. Package-visible for unit testing (no binary required).
     */
    static List<String> parseLangList(String output) {
        return output.lines()
            .map(String::strip)
            .filter(line -> line.matches("[A-Za-z0-9_]{2,32}"))
            .filter(lang -> !lang.equalsIgnoreCase("osd"))
            .distinct()
            .sorted()
            .toList();
    }

    private static String findTesseract() {
        if (tesseractSearched) return tesseractPath;
        synchronized (PdfOcr.class) {
            if (tesseractSearched) return tesseractPath;
            tesseractPath = locateTesseract();
            tesseractSearched = true;
            return tesseractPath;
        }
    }

    private static String locateTesseract() {
        if (tesseractOverride != null && Files.isExecutable(Path.of(tesseractOverride))) {
            return tesseractOverride;
        }
        for (String candidate : TESSERACT_CANDIDATES) {
            boolean explicitPath = candidate.contains("/") || candidate.contains("\\");
            if (explicitPath) {
                if (Files.isExecutable(Path.of(candidate))) return candidate;
            } else {
                try {
                    Process p = new ProcessBuilder(candidate, "--version")
                        .redirectErrorStream(true).start();
                    p.getInputStream().readAllBytes();
                    if (p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 0) return candidate;
                } catch (IOException ignored) {
                    // not on PATH; try next candidate
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Normalises and validates the tesseract language spec, defaulting when blank. Rejects anything
     * outside {@link #LANG_PATTERN} with a clear {@link PdfOperationException} (hardening — the value
     * is an arg-vector element, never shell-interpreted). Package-visible for unit testing.
     */
    static String validateLanguages(String languages) throws PdfOperationException {
        String lang = (languages == null || languages.isBlank()) ? DEFAULT_LANGUAGES : languages.strip();
        if (!LANG_PATTERN.matcher(lang).matches()) {
            throw new PdfOperationException(
                "Invalid OCR language(s): must be language codes separated by '+' "
                + "(e.g. 'eng' or 'eng+pol'), letters/digits/+/_/- only, up to 64 chars.");
        }
        return lang;
    }

    // --- public API -------------------------------------------------------

    /** OCR {@code opts.input()} to a searchable PDF written at {@code opts.output()}. */
    public static OcrResult execute(OcrOptions opts) throws PdfOperationException {
        try (PDDocument doc = PdfLoader.load(opts.input())) {
            int[] counts = ocr(doc, opts.languages(), opts.dpi(), null);
            OutputPaths.ensureParentDir(opts.output());
            doc.save(opts.output().toFile());
            return new OcrResult(opts.output(), counts[0], counts[1]);
        } catch (IOException e) {
            throw new PdfOperationException("OCR failed: " + e.getMessage(), e);
        }
    }

    /**
     * In-memory variant: OCR {@code pdf} and return the searchable PDF's bytes. Never touches disk
     * except for the per-page image file {@code tesseract} must read (an isolated, deleted temp dir).
     */
    public static byte[] executeBytes(byte[] pdf, String languages, int dpi)
            throws PdfOperationException {
        return executeBytes(pdf, languages, dpi, null);
    }

    /**
     * In-memory variant restricted to {@code pages} (0-based indices; {@code null} = every page).
     * Used by redaction's re-OCR: only the rasterised (redacted) pages lost their text layer, so
     * OCR-ing the untouched pages would waste a tesseract run per page <em>and</em> stack a second,
     * usually worse, invisible text layer over their still-intact original text (duplicated
     * extraction/search hits). Pages outside the set are left byte-for-byte alone.
     */
    public static byte[] executeBytes(byte[] pdf, String languages, int dpi, Set<Integer> pages)
            throws PdfOperationException {
        try (PDDocument doc = PdfLoader.load(pdf)) {
            ocr(doc, languages, dpi, pages);
            return PdfLoader.toBytes(doc);
        } catch (IOException e) {
            throw new PdfOperationException("OCR failed: " + e.getMessage(), e);
        }
    }

    // --- core algorithm ---------------------------------------------------

    /**
     * Adds an invisible text layer to the pages of {@code doc} in place ({@code pages} filters by
     * 0-based index; {@code null} = all). Returns {@code [pagesProcessed, words]}.
     */
    private static int[] ocr(PDDocument doc, String languages, int dpiIn, Set<Integer> pages)
            throws PdfOperationException {
        // An explicitly empty page set means "nothing to OCR" — succeed without requiring the
        // tesseract binary at all (callers may compute the set before probing availability).
        if (pages != null && pages.isEmpty()) return new int[]{ 0, 0 };
        // Validate the language spec first (fail-fast, independent of whether tesseract is present).
        String lang = validateLanguages(languages);
        String tesseract = findTesseract();
        if (tesseract == null) {
            throw new PdfOperationException(
                "OCR is unavailable: the 'tesseract' command is not installed. Install Tesseract OCR "
                + "to make scanned PDFs searchable.");
        }
        int dpi = Math.min(MAX_DPI, dpiIn > 0 ? dpiIn : DEFAULT_DPI);

        PDFont font = loadFont(doc);
        PDFRenderer renderer = new PDFRenderer(doc);
        int pageCount = doc.getNumberOfPages();
        int processed = 0;
        int wordCount = 0;

        Path work;
        try {
            work = Files.createTempDirectory("pdfconduit-ocr-");
        } catch (IOException e) {
            throw new PdfOperationException("Cannot prepare OCR working directory.", e);
        }
        try {
            for (int i = 0; i < pageCount; i++) {
                if (pages != null && !pages.contains(i)) continue;
                processed++;
                double scale = dpi / 72.0;
                BufferedImage img = renderer.renderImageWithDPI(i, dpi, ImageType.RGB);
                List<Word> words = recognise(tesseract, lang, img, work, i);
                if (words.isEmpty()) continue;

                PDPage page = doc.getPage(i);
                wordCount += drawInvisibleText(doc, page, font, words, scale);
            }
        } catch (IOException e) {
            throw new PdfOperationException("OCR failed: " + e.getMessage(), e);
        } finally {
            deleteRecursively(work);
        }
        return new int[]{ processed, wordCount };
    }

    /** Renders {@code img} to a temp PNG and runs {@code tesseract ... tsv}, parsing per-word boxes. */
    private static List<Word> recognise(String tesseract, String lang, BufferedImage img,
                                        Path work, int pageIndex) throws IOException, PdfOperationException {
        Path png = work.resolve("page-" + pageIndex + ".png");
        ImageIO.write(img, "png", png.toFile());

        Process p = new ProcessBuilder(
            tesseract, png.toString(), "stdout", "-l", lang, "tsv")
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
        byte[] out;
        try {
            out = p.getInputStream().readAllBytes();
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                p.waitFor(5, TimeUnit.SECONDS);
                throw new PdfOperationException("tesseract timed out on page " + (pageIndex + 1) + ".");
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new PdfOperationException("OCR was interrupted.", e);
        }
        if (p.exitValue() != 0) {
            // tesseract's stderr is discarded (can carry library noise); keep the message clean.
            throw new PdfOperationException("tesseract failed on page " + (pageIndex + 1) + ".");
        }
        return parseTsv(new String(out, java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Parses tesseract TSV output. Columns:
     * {@code level page block par line word left top width height conf text}. We keep only
     * {@code level == 5} (word) rows with positive confidence and non-blank text.
     */
    private static List<Word> parseTsv(String tsv) {
        List<Word> words = new ArrayList<>();
        for (String line : tsv.split("\n", -1)) {
            if (line.isEmpty() || line.startsWith("level\t")) continue;   // header / blank
            String[] c = line.split("\t", 12);
            if (c.length < 12) continue;
            try {
                if (Integer.parseInt(c[0].trim()) != 5) continue;   // word rows only
                float conf = Float.parseFloat(c[10].trim());
                if (conf < 0) continue;
                String text = c[11];
                if (text == null || text.isBlank()) continue;
                int left = Integer.parseInt(c[6].trim());
                int top = Integer.parseInt(c[7].trim());
                int width = Integer.parseInt(c[8].trim());
                int height = Integer.parseInt(c[9].trim());
                if (width <= 0 || height <= 0) continue;
                words.add(new Word(text, left, top, width, height));
            } catch (NumberFormatException ignored) {
                // malformed row — skip
            }
        }
        return words;
    }

    /**
     * Appends the invisible text layer for one page. A single content-stream transform maps
     * <em>displayed</em> (rotation-applied, bottom-left origin) point coordinates back into the
     * page's unrotated user space, so we can position every word using its displayed baseline and
     * let the CTM carry the page rotation. Returns the number of words actually placed.
     */
    private static int drawInvisibleText(PDDocument doc, PDPage page, PDFont font,
                                         List<Word> words, double scale) throws IOException {
        PDRectangle box = page.getCropBox();
        float ox = box.getLowerLeftX(), oy = box.getLowerLeftY();
        float w0 = box.getWidth(), h0 = box.getHeight();
        int rot = ((page.getRotation() % 360) + 360) % 360;

        // Displayed size (rotation applied) — matches the rendered image's orientation.
        float dispW = (rot == 90 || rot == 270) ? h0 : w0;
        float dispH = (rot == 90 || rot == 270) ? w0 : h0;

        // Matrix mapping displayed (a,b) bottom-left points -> unrotated page user space (see class doc).
        Matrix m = switch (rot) {
            case 90  -> new Matrix(0, 1, -1, 0, ox + w0, oy);
            case 180 -> new Matrix(-1, 0, 0, -1, ox + w0, oy + h0);
            case 270 -> new Matrix(0, -1, 1, 0, ox, oy + h0);
            default  -> new Matrix(1, 0, 0, 1, ox, oy);
        };

        int placed = 0;
        try (PDPageContentStream cs = new PDPageContentStream(doc, page, AppendMode.APPEND, true, true)) {
            cs.saveGraphicsState();
            cs.transform(m);
            cs.setRenderingMode(RenderingMode.NEITHER);   // text render mode 3 = invisible
            cs.setFont(font, 1);                          // real size set per word via the text matrix
            for (Word word : words) {
                String text = encodable(font, word.text());
                if (text.isEmpty()) continue;

                float boxWidthPt = (float) (word.width() / scale);
                float a = (float) (word.left() / scale);                       // displayed x (points)
                float b = dispH - (float) ((word.top() + word.height()) / scale); // displayed baseline y

                float glyphWidth1000 = safeStringWidth(font, text);
                if (glyphWidth1000 <= 0) continue;
                float fontSize = boxWidthPt * 1000f / glyphWidth1000;
                if (!(fontSize > 0) || Float.isInfinite(fontSize)) continue;

                cs.beginText();
                // Scale the unit font to fontSize and translate to the word's displayed baseline.
                cs.setTextMatrix(new Matrix(fontSize, 0, 0, fontSize, a, b));
                cs.showText(text);
                cs.endText();
                placed++;
            }
            cs.restoreGraphicsState();
        }
        return placed;
    }

    // --- font handling ----------------------------------------------------

    private static PDFont loadFont(PDDocument doc) throws PdfOperationException {
        try (InputStream in = PdfOcr.class.getResourceAsStream(FONT_RESOURCE)) {
            if (in == null) {
                throw new PdfOperationException("OCR font resource missing: " + FONT_RESOURCE);
            }
            return PDType0Font.load(doc, in, true);   // embed a subset
        } catch (IOException e) {
            throw new PdfOperationException("Cannot load OCR font: " + e.getMessage(), e);
        }
    }

    /** Keeps only the code points the embedded font can encode (drops the rest). */
    private static String encodable(PDFont font, String text) {
        StringBuilder sb = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            String s = new String(Character.toChars(cp));
            try {
                font.getStringWidth(s);   // throws if unencodable
                sb.append(s);
            } catch (Exception ignored) {
                // glyph not in the font (e.g. CJK without a matching font) — skip it
            }
        });
        return sb.toString();
    }

    private static float safeStringWidth(PDFont font, String text) {
        try {
            return font.getStringWidth(text);
        } catch (Exception e) {
            return -1;
        }
    }

    // --- small helpers ----------------------------------------------------

    /** One recognised word: text plus its pixel bounding box (top-left origin) in the rendered image. */
    private record Word(String text, int left, int top, int width, int height) {}

    private static void deleteRecursively(Path dir) {
        if (dir == null) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }
}
