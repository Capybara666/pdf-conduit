package com.pdfconduit.core.operations;

import com.pdfconduit.core.convert.DocumentConverter;
import com.pdfconduit.core.exception.PdfOperationException;
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
 * Exports a PDF's text content as TXT (PDFBox {@link PDFTextStripper}, no dependency)
 * or DOCX (LibreOffice). The reverse direction of office→PDF conversion.
 *
 * <p>For TXT the page range is honoured (arbitrary page lists are extracted page by
 * page); DOCX always exports the whole document — a LibreOffice limitation.
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

        if (opts.format() == TextFormat.DOCX) {
            DocumentConverter.convertPdfTo(opts.input(),
                opts.format().sofficeFormat(), opts.format().extension(), out);
            return new PdfToTextResult(out);
        }

        writeText(opts, out);
        return new PdfToTextResult(out);
    }

    private static void writeText(PdfToTextOptions opts, Path out) throws PdfOperationException {
        try (PDDocument doc = PdfLoader.load(opts.input())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text;
            if (opts.pages().isAll()) {
                text = stripper.getText(doc);
            } else {
                StringBuilder sb = new StringBuilder();
                for (int pageNum : opts.pages().pageNumbers()) {
                    stripper.setStartPage(pageNum);
                    stripper.setEndPage(pageNum);
                    sb.append(stripper.getText(doc));
                }
                text = sb.toString();
            }
            Files.writeString(out, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PdfOperationException("Text export failed: " + e.getMessage(), e);
        }
    }
}
