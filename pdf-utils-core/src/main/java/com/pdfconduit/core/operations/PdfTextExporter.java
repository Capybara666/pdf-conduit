package com.pdfconduit.core.operations;

import com.pdfconduit.core.convert.DocumentConverter;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.PageRange;
import com.pdfconduit.core.model.PdfToTextOptions;
import com.pdfconduit.core.model.PdfToTextResult;
import com.pdfconduit.core.model.TextFormat;
import com.pdfconduit.core.util.PdfLoader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Exports a PDF's text content as TXT or DOCX. The text is always extracted with
 * PDFBox ({@link PDFTextStripper}, honouring the page range); for DOCX that text is
 * then wrapped into a Word document by LibreOffice (txt→docx).
 *
 * <p>Word output is therefore plain, editable text — not a visual reproduction of the
 * PDF's layout. That is deliberate: importing a PDF's layout into Word
 * (LibreOffice's {@code writer_pdf_import}) yields a frame-heavy document that even
 * LibreOffice can hang on when reopening.
 */
public final class PdfTextExporter {

    private PdfTextExporter() {}

    public static PdfToTextResult execute(PdfToTextOptions opts) throws PdfOperationException {
        Path out = opts.outputDir().resolve(opts.baseName() + "." + opts.format().extension());
        try {
            Files.createDirectories(opts.outputDir());
        } catch (IOException e) {
            throw new PdfOperationException("Cannot create output folder: " + e.getMessage(), e);
        }

        String text = extractText(opts);

        if (opts.format() == TextFormat.TXT) {
            try {
                Files.writeString(out, text, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new PdfOperationException("Text export failed: " + e.getMessage(), e);
            }
            return new PdfToTextResult(out);
        }

        // DOCX: wrap the extracted text into a Word document via LibreOffice (txt→docx).
        Path tempTxt;
        try {
            tempTxt = Files.createTempFile("pdfconduit-text-", ".txt");
            Files.writeString(tempTxt, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PdfOperationException("Text export failed: " + e.getMessage(), e);
        }
        try {
            DocumentConverter.convertTo(tempTxt, opts.format().sofficeFormat(),
                opts.format().extension(), out);
        } finally {
            try { Files.deleteIfExists(tempTxt); } catch (IOException ignored) {}
        }
        return new PdfToTextResult(out);
    }

    /**
     * In-memory variant: extract the text of {@code pdf} (honouring {@code pages}) as a String.
     * Pure in-memory (PDFBox {@link PDFTextStripper}); no disk touched.
     */
    public static String extractTextBytes(byte[] pdf, PageRange pages) throws PdfOperationException {
        try (PDDocument doc = PdfLoader.load(pdf)) {
            return extractText(doc, pages);
        } catch (IOException e) {
            throw new PdfOperationException("Text export failed: " + e.getMessage(), e);
        }
    }

    /**
     * In-memory variant: export the text of {@code pdf} in {@code format} and return the bytes.
     *
     * <p>TXT is produced purely in memory (UTF-8). DOCX still requires LibreOffice, which is an
     * external process that must read/write real files, so this is the one documented disk touch:
     * the extracted text is written to an isolated temp dir, converted, read back, and the dir
     * deleted in {@code finally}.
     */
    public static byte[] toTextBytes(byte[] pdf, TextFormat format, PageRange pages)
            throws PdfOperationException {
        String text = extractTextBytes(pdf, pages);
        if (format == TextFormat.TXT) {
            return text.getBytes(StandardCharsets.UTF_8);
        }

        // DOCX: the LibreOffice (disk) exception.
        Path dir;
        try {
            dir = Files.createTempDirectory("pdfconduit-text-");
        } catch (IOException e) {
            throw new PdfOperationException("Text export failed: " + e.getMessage(), e);
        }
        try {
            Path txt = dir.resolve("text.txt");
            Path out = dir.resolve("text." + format.extension());
            Files.writeString(txt, text, StandardCharsets.UTF_8);
            DocumentConverter.convertTo(txt, format.sofficeFormat(), format.extension(), out);
            return Files.readAllBytes(out);
        } catch (IOException e) {
            throw new PdfOperationException("Text export failed: " + e.getMessage(), e);
        } finally {
            deleteRecursively(dir);
        }
    }

    private static void deleteRecursively(Path dir) {
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    private static String extractText(PdfToTextOptions opts) throws PdfOperationException {
        try (PDDocument doc = PdfLoader.load(opts.input())) {
            return extractText(doc, opts.pages());
        } catch (IOException e) {
            throw new PdfOperationException("Text export failed: " + e.getMessage(), e);
        }
    }

    /** The shared extraction: all pages, or the given 1-based pages, of {@code doc}. */
    private static String extractText(PDDocument doc, PageRange pages) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        if (pages.isAll()) {
            return stripper.getText(doc);
        }
        StringBuilder sb = new StringBuilder();
        for (int pageNum : pages.pageNumbers()) {
            stripper.setStartPage(pageNum);
            stripper.setEndPage(pageNum);
            sb.append(stripper.getText(doc));
        }
        return sb.toString();
    }
}
