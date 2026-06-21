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
        "*.doc", "*.docx", "*.odt", "*.rtf", "*.txt", "*.md",
        "*.xls", "*.xlsx", "*.ods", "*.csv",
        "*.ppt", "*.pptx", "*.odp", "*.html", "*.htm");

    /** Everything that {@link #ensurePdf} can turn into a PDF. */
    public static final List<String> ALL_GLOBS = concat(PDF_GLOBS, IMAGE_GLOBS, OFFICE_GLOBS);

    private static final Set<String> IMAGE_EXTS = extsOf(IMAGE_GLOBS);
    private static final Set<String> OFFICE_EXTS = extsOf(OFFICE_GLOBS);

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
            case OFFICE -> convertWithLibreOffice(source, targetPdf);
            case UNSUPPORTED -> throw new PdfOperationException(
                "Unsupported file type: " + source.getFileName());
        }
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
        // Office → PDF: LibreOffice auto-detects the input filter.
        runLibreOffice(source, null, "pdf", "pdf", target);
    }

    /**
     * Converts a PDF to another format via a headless LibreOffice — e.g.
     * {@code convertPdfTo(pdf, "docx", "docx", out)} — writing the result to {@code target}.
     * The reverse direction of {@link #ensurePdf}; same soffice plumbing.
     *
     * <p>A PDF must be opened with the Writer import filter, otherwise LibreOffice loads it
     * into Draw (which has no Word/text export filter and fails with "no export filter").
     */
    public static void convertPdfTo(Path source, String targetFormat, String targetExt, Path target)
            throws PdfOperationException {
        runLibreOffice(source, "writer_pdf_import", targetFormat, targetExt, target);
    }

    private static void runLibreOffice(Path source, String inFilter, String targetFormat,
                                       String targetExt, Path target) throws PdfOperationException {
        String soffice = findSoffice();
        if (soffice == null) {
            throw new PdfOperationException("Cannot convert " + source.getFileName()
                + ": LibreOffice is not installed. Install LibreOffice (the 'soffice' command) "
                + "to convert documents.");
        }

        Path workDir;
        try {
            workDir = Files.createTempDirectory("pdfconduit-lo-");
        } catch (IOException e) {
            throw new PdfOperationException("Cannot create temp dir: " + e.getMessage(), e);
        }

        try {
            // A per-call user profile lets conversions run even while another
            // LibreOffice instance is open, and lets several run concurrently.
            Path profile = workDir.resolve("profile");
            List<String> cmd = new ArrayList<>(List.of(
                soffice, "--headless", "--norestore", "--nolockcheck",
                "-env:UserInstallation=file://" + profile.toAbsolutePath()));
            if (inFilter != null) cmd.add("--infilter=" + inFilter);
            cmd.addAll(List.of("--convert-to", targetFormat,
                "--outdir", workDir.toString(), source.toString()));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String log = new String(p.getInputStream().readAllBytes());
            if (!p.waitFor(180, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new PdfOperationException("LibreOffice timed out converting "
                    + source.getFileName());
            }
            if (p.exitValue() != 0) {
                throw new PdfOperationException("LibreOffice failed converting "
                    + source.getFileName() + (log.isBlank() ? "" : " — " + log.strip()));
            }

            Path produced = locateProduced(workDir, source, targetExt);
            if (produced == null) {
                throw new PdfOperationException("LibreOffice produced no " + targetExt
                    + " for " + source.getFileName());
            }
            OutputPaths.ensureParentDir(target);
            Files.move(produced, target, REPLACE_EXISTING);
        } catch (IOException e) {
            throw new PdfOperationException("Document conversion failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
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
