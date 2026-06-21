package com.pdfconduit.core.operations;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import com.pdfconduit.core.model.MetadataOptions;
import com.pdfconduit.core.model.PdfMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PdfMetadataTest {

    @TempDir Path tmp;

    @Test
    void readReturnsExistingMetadata() throws Exception {
        Path src = pdfWithInfo("Hello", "Alice");
        PdfMetadata md = PdfMetadataEditor.read(src);
        assertEquals("Hello", md.title());
        assertEquals("Alice", md.author());
    }

    @Test
    void editSetsFields() throws Exception {
        Path out = tmp.resolve("out.pdf");
        PdfMetadataEditor.execute(new MetadataOptions(
            pdf(), "T", "A", "S", "k1,k2", false, out));
        PdfMetadata md = PdfMetadataEditor.read(out);
        assertEquals("T", md.title());
        assertEquals("A", md.author());
        assertEquals("S", md.subject());
        assertEquals("k1,k2", md.keywords());
    }

    @Test
    void editLeavesNullFieldsUnchanged() throws Exception {
        Path out = tmp.resolve("out.pdf");
        PdfMetadataEditor.execute(new MetadataOptions(
            pdfWithInfo("Orig", "Auth"), null, "NewAuthor", null, null, false, out));
        PdfMetadata md = PdfMetadataEditor.read(out);
        assertEquals("Orig", md.title());        // untouched
        assertEquals("NewAuthor", md.author());  // replaced
    }

    @Test
    void stripClearsMetadata() throws Exception {
        Path out = tmp.resolve("stripped.pdf");
        PdfMetadataEditor.execute(new MetadataOptions(
            pdfWithInfo("Secret", "Spy"), null, null, null, null, true, out));
        PdfMetadata md = PdfMetadataEditor.read(out);
        assertTrue(md.title() == null || md.title().isBlank());
        assertTrue(md.author() == null || md.author().isBlank());
    }

    private Path pdf() throws IOException {
        Path p = tmp.resolve("plain-" + System.nanoTime() + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(p.toFile());
        }
        return p;
    }

    private Path pdfWithInfo(String title, String author) throws IOException {
        Path p = tmp.resolve("info-" + System.nanoTime() + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            PDDocumentInformation info = doc.getDocumentInformation();
            info.setTitle(title);
            info.setAuthor(author);
            doc.save(p.toFile());
        }
        return p;
    }
}
