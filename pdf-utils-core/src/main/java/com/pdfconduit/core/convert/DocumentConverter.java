package com.pdfconduit.core.convert;

import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.ImageToPdfOptions;
import com.pdfconduit.core.model.PageSize;
import com.pdfconduit.core.operations.ImageToPdfConverter;
import com.pdfconduit.core.util.OutputPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/**
 * Turns any supported file into a PDF, so every operation can accept more than
 * just PDFs and images:
 *
 * <ul>
 *   <li><b>PDF</b> — passed through unchanged.</li>
 *   <li><b>Images</b> (png/jpg/webp/tiff/bmp/gif) — rendered to a PDF with
 *       {@link ImageToPdfConverter}.</li>
 *   <li><b>Office &amp; text documents</b> (doc/docx/odt/rtf/txt/xls/xlsx/…)
 *       — converted with a headless LibreOffice, if one is installed.</li>
 * </ul>
 *
 * <p>Stateless and thread-safe (LibreOffice is invoked with a per-call user
 * profile so concurrent conversions don't clash).
 */
public final class DocumentConverter {

    private DocumentConverter() {}

    // Glob patterns, suitable for file-chooser extension filters.
    public static final List<String> PDF_GLOBS = List.of("*.pdf");
    public static final List<String> IMAGE_GLOBS = List.of(
        "*.png", "*.jpg", "*.jpeg", "*.webp", "*.tif", "*.tiff", "*.bmp", "*.gif");
    public static final List<String> OFFICE_GLOBS = List.of(
        "*.doc", "*.docx", "*.odt", "*.rtf", "*.txt", "*.md", "*.markdown",
        "*.xls", "*.xlsx", "*.ods", "*.csv",
        "*.ppt", "*.pptx", "*.odp", "*.html", "*.htm");

    // Markdown gets rendered to HTML before the LibreOffice HTML→PDF leg (LibreOffice would
    // otherwise import raw .md as unformatted plain text). Kept classified as OFFICE so the same
    // gating (pdfconduit.web.office.enabled) and concurrency guards still apply.
    private static final Set<String> MARKDOWN_EXTS = Set.of("md", "markdown");

    /** Everything that {@link #ensurePdf} can turn into a PDF. */
    public static final List<String> ALL_GLOBS = concat(PDF_GLOBS, IMAGE_GLOBS, OFFICE_GLOBS);

    private static final Set<String> IMAGE_EXTS = extsOf(IMAGE_GLOBS);
    private static final Set<String> OFFICE_EXTS = extsOf(OFFICE_GLOBS);

    /**
     * Spreadsheet extensions that get the tuned Calc PDF export (see {@link #pdfConvertSpec}).
     * A subset of {@link #OFFICE_EXTS}; the rest (doc/ppt/txt/html/…) use the generic PDF filter.
     */
    private static final Set<String> SPREADSHEET_EXTS = Set.of("xls", "xlsx", "ods", "csv", "fods");

    public enum Kind { PDF, IMAGE, OFFICE, UNSUPPORTED }

    public static Kind classify(Path file) {
        String ext = extensionOf(file);
        if (ext.equals("pdf")) return Kind.PDF;
        if (IMAGE_EXTS.contains(ext)) return Kind.IMAGE;
        if (OFFICE_EXTS.contains(ext)) return Kind.OFFICE;
        return Kind.UNSUPPORTED;
    }

    public static boolean isSupported(Path file) { return classify(file) != Kind.UNSUPPORTED; }

    public static boolean isPdf(Path file) { return classify(file) == Kind.PDF; }

    /**
     * Returns a PDF for {@code source}: the file itself if it is already a PDF,
     * otherwise a freshly converted temp file whose path is added to
     * {@code temps} so the caller can delete it when done.
     */
    public static Path ensurePdf(Path source, PageSize imageSize, List<Path> temps)
            throws PdfOperationException {
        if (classify(source) == Kind.PDF) return source;
        Path tmp;
        try {
            tmp = Files.createTempFile("pdfconduit-", ".pdf");
        } catch (IOException e) {
            throw new PdfOperationException("Cannot create temp file: " + e.getMessage(), e);
        }
        toPdf(source, tmp, imageSize);
        temps.add(tmp);
        return tmp;
    }

    /** Writes a PDF rendering of {@code source} to {@code targetPdf}. */
    public static void toPdf(Path source, Path targetPdf, PageSize imageSize)
            throws PdfOperationException {
        switch (classify(source)) {
            case PDF -> {
                try {
                    OutputPaths.ensureParentDir(targetPdf);
                    Files.copy(source, targetPdf, REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new PdfOperationException("Cannot copy PDF: " + e.getMessage(), e);
                }
            }
            case IMAGE -> ImageToPdfConverter.execute(new ImageToPdfOptions(
                List.of(source), imageSize == null ? PageSize.FIT : imageSize, targetPdf));
            case OFFICE -> convertOfficeSource(source, targetPdf);
            case UNSUPPORTED -> throw new PdfOperationException(
                "Unsupported file type: " + source.getFileName());
        }
    }

    /**
     * In-memory analog of {@link #ensurePdf}: returns PDF bytes for {@code data}, whose type is
     * inferred from {@code filename}'s extension.
     *
     * <ul>
     *   <li><b>PDF</b> — the bytes are returned unchanged (no disk).</li>
     *   <li><b>Image</b> — rendered to a PDF entirely in memory.</li>
     *   <li><b>Office/text</b> — the one documented disk exception: LibreOffice is an external
     *       process that must read/write real files, so the bytes are written to an isolated
     *       {@link Files#createTempDirectory temp dir}, converted, read back, and the dir is
     *       deleted in {@code finally}. Nothing else touches disk.</li>
     * </ul>
     */
    public static byte[] ensurePdfBytes(byte[] data, String filename, PageSize imageSize)
            throws PdfOperationException {
        Kind kind = classify(Path.of(filename == null ? "" : filename));
        return switch (kind) {
            case PDF -> data;
            case IMAGE -> ImageToPdfConverter.executeBytes(
                List.of(data), imageSize == null ? PageSize.FIT : imageSize);
            case OFFICE -> officeToPdfBytes(data, filename);
            case UNSUPPORTED -> throw new PdfOperationException("Unsupported file type: " + filename);
        };
    }

    /** Office → PDF bytes via an isolated temp dir and a headless LibreOffice; the sole disk touch. */
    private static byte[] officeToPdfBytes(byte[] data, String filename) throws PdfOperationException {
        Path dir;
        try {
            dir = Files.createTempDirectory("pdfconduit-mem-");
        } catch (IOException e) {
            // Keep the raw temp path / OS error out of the client-visible message.
            throw new PdfOperationException("Cannot prepare document conversion.", e);
        }
        try {
            Path out = dir.resolve("output.pdf");
            Path in;
            if (isMarkdown(filename)) {
                // Render Markdown → HTML first, then let LibreOffice import the HTML (preserving
                // headings/lists/tables) rather than the raw .md as unformatted plain text.
                String html = MarkdownConverter.toHtml(new String(data, java.nio.charset.StandardCharsets.UTF_8));
                in = dir.resolve("input.html");
                Files.writeString(in, html);
            } else {
                in = dir.resolve(sanitize(filename));
                Files.write(in, data);
            }
            convertWithLibreOffice(in, out);
            return Files.readAllBytes(out);
        } catch (IOException e) {
            // e.getMessage() can contain the temp working directory path — do not surface it.
            throw new PdfOperationException("Document conversion failed for " + sanitize(filename) + ".", e);
        } finally {
            deleteRecursively(dir);
        }
    }

    /** A safe, extension-preserving file name for the temp input (no path separators). */
    private static String sanitize(String filename) {
        String name = (filename == null || filename.isBlank()) ? "input" : filename;
        name = Path.of(name).getFileName().toString();
        return name.isBlank() ? "input" : name;
    }

    /** Whether office/document conversion is possible on this machine. */
    public static boolean officeConversionAvailable() { return findSoffice() != null; }

    /** The resolved LibreOffice executable (override or auto-detected), or {@code null}. */
    public static String sofficePath() { return findSoffice(); }

    /**
     * Sets a user-supplied LibreOffice path, tried before the built-in candidates. Blank
     * clears it. Resets the cached search so the change takes effect immediately. The app
     * persists this (a GUI preference); core only holds the live value.
     */
    public static void setSofficeOverride(String path) {
        synchronized (DocumentConverter.class) {
            sofficeOverride = (path == null || path.isBlank()) ? null : path.strip();
            sofficeSearched = false;
            sofficePath = null;
        }
    }

    // --- LibreOffice ------------------------------------------------------

    private static final String[] SOFFICE_CANDIDATES = {
        "soffice", "libreoffice",
        "/usr/bin/soffice", "/usr/bin/libreoffice", "/snap/bin/libreoffice",
        "/opt/libreoffice/program/soffice",
        "/Applications/LibreOffice.app/Contents/MacOS/soffice",
        "C:\\Program Files\\LibreOffice\\program\\soffice.exe",
        "C:\\Program Files (x86)\\LibreOffice\\program\\soffice.exe"
    };

    private static volatile boolean sofficeSearched;
    private static volatile String sofficePath;
    private static volatile String sofficeOverride;   // user-supplied path, tried first

    /**
     * Bounds how many headless LibreOffice conversions run at once, process-wide. LibreOffice is
     * heavy and each conversion spawns an external process; without a cap a burst of office/text
     * requests can spawn an unbounded number of {@code soffice} processes and exhaust the host.
     * Replaced wholesale by {@link #setMaxConcurrentConversions(int)} (the web layer aligns this
     * with {@code pdfconduit.web.office.max-concurrent}); in-flight conversions release to the
     * semaphore they acquired, so a resize never loses a permit.
     */
    private static final int DEFAULT_MAX_CONVERSIONS = 4;
    private static volatile Semaphore conversionPermits = new Semaphore(DEFAULT_MAX_CONVERSIONS, true);

    /** Hard wall-clock timeout for a single {@code soffice} invocation (best-effort; process is killed). */
    private static final int DEFAULT_CONVERSION_TIMEOUT_SECONDS = 120;
    private static volatile int conversionTimeoutSeconds = DEFAULT_CONVERSION_TIMEOUT_SECONDS;

    /**
     * Caps the number of concurrent LibreOffice conversions across the whole JVM. Values &lt; 1 are
     * clamped to 1. Called by the web backend at startup to match its office concurrency setting.
     */
    public static void setMaxConcurrentConversions(int max) {
        conversionPermits = new Semaphore(Math.max(1, max), true);
    }

    /**
     * Sets the per-conversion wall-clock timeout (seconds); on expiry the {@code soffice} process
     * is force-killed. Values &lt; 1 are clamped to 1. Kept at or below the caller's own processing
     * deadline so a stuck conversion cannot outlive the request that started it.
     */
    public static void setConversionTimeoutSeconds(int seconds) {
        conversionTimeoutSeconds = Math.max(1, seconds);
    }

    private static String findSoffice() {
        if (sofficeSearched) return sofficePath;
        synchronized (DocumentConverter.class) {
            if (sofficeSearched) return sofficePath;
            sofficePath = locateSoffice();
            sofficeSearched = true;
            return sofficePath;
        }
    }

    private static String locateSoffice() {
        if (sofficeOverride != null && Files.isExecutable(Path.of(sofficeOverride))) {
            return sofficeOverride;
        }
        for (String candidate : SOFFICE_CANDIDATES) {
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

    private static void convertWithLibreOffice(Path source, Path target)
            throws PdfOperationException {
        String spec = pdfConvertSpec(source);
        if (spec.equals("pdf")) {
            runLibreOffice(source, "pdf", "pdf", target);
            return;
        }
        // Tuned spreadsheet export. If a LibreOffice too old to parse the JSON filter options
        // rejects the tuned spec, fall back to the plain PDF filter so spreadsheet conversion
        // never regresses — it only loses the single-page layout, it never fails outright.
        try {
            runLibreOffice(source, spec, "pdf", target);
        } catch (PdfOperationException tuned) {
            runLibreOffice(source, "pdf", "pdf", target);
        }
    }

    /**
     * The {@code --convert-to} value used to export {@code source} to PDF.
     *
     * <p>Plain office/text documents (doc/docx/odt/rtf/txt/md/ppt/pptx/odp/html) export with the
     * generic {@code "pdf"} filter — unchanged behaviour.
     *
     * <p>Spreadsheets (xls/xlsx/ods/csv/fods) are the ugly case this method fixes: LibreOffice's
     * default Calc PDF export honours each sheet's print ranges, so a wide sheet spills its columns
     * across many pages and is cut at the page edge, and fit-to-page is ignored. We instead invoke
     * the Calc-specific export filter ({@code calc_pdf_Export}) with the {@code SinglePageSheets}
     * option, which lays each whole sheet onto a single PDF page — no column spill, no cut-off wide
     * sheets. Delivered as a JSON filter-options string, understood by LibreOffice 7.4+; older
     * builds are covered by the plain-{@code pdf} fallback in {@link #convertWithLibreOffice}.
     */
    private static String pdfConvertSpec(Path source) {
        if (SPREADSHEET_EXTS.contains(extensionOf(source))) {
            return "pdf:calc_pdf_Export:{\"SinglePageSheets\":{\"type\":\"boolean\",\"value\":\"true\"}}";
        }
        return "pdf";
    }

    /** True for a Markdown source (by extension); rendered to HTML before the soffice PDF leg. */
    static boolean isMarkdown(String filename) {
        return filename != null && MARKDOWN_EXTS.contains(extensionOf(Path.of(filename)));
    }

    /**
     * Converts an OFFICE-classified {@code source} to PDF. Markdown is first rendered to a
     * self-contained HTML file (in an isolated temp dir) and that HTML is handed to LibreOffice —
     * so headings/lists/tables/code survive instead of arriving as raw plain text; every other
     * office/text type goes straight to LibreOffice.
     */
    private static void convertOfficeSource(Path source, Path target) throws PdfOperationException {
        if (!isMarkdown(source.getFileName().toString())) {
            convertWithLibreOffice(source, target);
            return;
        }
        Path dir;
        try {
            dir = Files.createTempDirectory("pdfconduit-md-");
        } catch (IOException e) {
            throw new PdfOperationException("Cannot prepare document conversion.", e);
        }
        try {
            String html = MarkdownConverter.toHtml(Files.readString(source));
            Path htmlFile = dir.resolve(stem(source) + ".html");
            Files.writeString(htmlFile, html);
            convertWithLibreOffice(htmlFile, target);
        } catch (IOException e) {
            throw new PdfOperationException("Document conversion failed for " + source.getFileName() + ".", e);
        } finally {
            deleteRecursively(dir);
        }
    }

    /**
     * Converts a LibreOffice-readable {@code source} to another format — e.g.
     * {@code convertTo(txt, "docx", "docx", out)} — writing the result to {@code target}.
     *
     * <p>Note: this is <em>not</em> for PDF inputs. A headless LibreOffice opens a PDF in
     * Draw, whose only export is back to PDF; forcing the Writer import filter instead
     * produces a frame-heavy document that even LibreOffice struggles to reopen. To make a
     * Word file from a PDF, extract its text first (see {@code PdfTextExporter}) and convert
     * that text here.
     */
    public static void convertTo(Path source, String targetFormat, String targetExt, Path target)
            throws PdfOperationException {
        runLibreOffice(source, targetFormat, targetExt, target);
    }

    private static void runLibreOffice(Path source, String targetFormat,
                                       String targetExt, Path target) throws PdfOperationException {
        String soffice = findSoffice();
        if (soffice == null) {
            throw new PdfOperationException("Cannot convert " + source.getFileName()
                + ": LibreOffice is not installed. Install LibreOffice (the 'soffice' command) "
                + "to convert documents.");
        }

        // Bound how many soffice processes run at once, JVM-wide. Interruptible so a caller that
        // times out (Future.cancel(true)) is released rather than blocking forever on the permit.
        Semaphore permits = conversionPermits;
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PdfOperationException("Document conversion was interrupted.", e);
        }
        try {
            runLibreOfficeLocked(soffice, source, targetFormat, targetExt, target);
        } finally {
            permits.release();
        }
    }

    private static void runLibreOfficeLocked(String soffice, Path source, String targetFormat,
                                             String targetExt, Path target) throws PdfOperationException {
        Path workDir;
        try {
            workDir = Files.createTempDirectory("pdfconduit-lo-");
        } catch (IOException e) {
            // Do not surface the raw temp path / OS error to callers (it can reach an API client).
            throw new PdfOperationException("Cannot prepare document conversion.", e);
        }

        Process p = null;
        try {
            // A per-call user profile lets conversions run even while another
            // LibreOffice instance is open, and lets several run concurrently.
            Path profile = workDir.resolve("profile");
            p = new ProcessBuilder(
                soffice, "--headless", "--norestore", "--nolockcheck",
                "-env:UserInstallation=file://" + profile.toAbsolutePath(),
                "--convert-to", targetFormat, "--outdir", workDir.toString(), source.toString())
                .redirectErrorStream(true).start();
            String log = new String(p.getInputStream().readAllBytes());
            if (!p.waitFor(conversionTimeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                p.waitFor(5, TimeUnit.SECONDS);
                throw new PdfOperationException("LibreOffice timed out converting "
                    + source.getFileName());
            }
            if (p.exitValue() != 0) {
                // The soffice stderr/stdout (`log`) can leak temp paths / library diagnostics — keep
                // it out of the client-visible message; the exception itself carries no secrets.
                throw new PdfOperationException("LibreOffice failed converting " + source.getFileName());
            }

            Path produced = locateProduced(workDir, source, targetExt);
            if (produced == null) {
                throw new PdfOperationException("LibreOffice produced no " + targetExt
                    + " for " + source.getFileName());
            }
            OutputPaths.ensureParentDir(target);
            Files.move(produced, target, REPLACE_EXISTING);
        } catch (IOException e) {
            // e.getMessage() can contain the temp working directory path — do not surface it.
            throw new PdfOperationException("Document conversion failed for " + source.getFileName() + ".", e);
        } catch (InterruptedException e) {
            if (p != null) p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new PdfOperationException("Document conversion was interrupted.", e);
        } finally {
            deleteRecursively(workDir);
        }
    }

    private static Path locateProduced(Path dir, Path source, String ext) throws IOException {
        Path expected = dir.resolve(stem(source) + "." + ext);
        if (Files.exists(expected)) return expected;
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(f -> f.getFileName().toString().toLowerCase().endsWith("." + ext))
                    .findFirst().orElse(null);
        }
    }

    // --- small helpers ----------------------------------------------------

    private static String extensionOf(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : "";
    }

    private static String stem(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }

    private static Set<String> extsOf(List<String> globs) {
        return globs.stream().map(g -> g.replace("*.", "")).collect(Collectors.toSet());
    }

    @SafeVarargs
    private static List<String> concat(List<String>... lists) {
        List<String> all = new ArrayList<>();
        for (List<String> l : lists) all.addAll(l);
        return List.copyOf(all);
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }
}
