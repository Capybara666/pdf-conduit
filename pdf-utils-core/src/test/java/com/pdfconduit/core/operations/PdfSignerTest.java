package com.pdfconduit.core.operations;

import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.SignPlacement;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PdfSignerTest {

    @Test
    void fillsFieldAndStampsSignatureImage() throws Exception {
        byte[] pdf = formPdf();
        byte[] sig = signaturePng();

        byte[] out = PdfSigner.executeBytes(pdf, List.of(sig),
            List.of(new SignPlacement(0, 0, 100, 500, 180, 60)),
            Map.of("fullName", "Ada Lovelace"), false);

        try (PDDocument doc = Loader.loadPDF(out)) {
            // The AcroForm field value is set (form NOT flattened, so it is still queryable).
            PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
            assertNotNull(form, "form should survive an un-flattened sign");
            assertEquals("Ada Lovelace", form.getField("fullName").getValueAsString());
            // The signature image XObject is embedded on the page.
            assertTrue(hasImage(doc.getPage(0)), "signature image should be stamped on the page");
        }
    }

    @Test
    void flattenBakesFieldValueIntoStaticContentAndKeepsSignature() throws Exception {
        byte[] pdf = formPdf();
        byte[] sig = signaturePng();

        byte[] out = PdfSigner.executeBytes(pdf, List.of(sig),
            List.of(new SignPlacement(0, 0, 100, 500, 180, 60)),
            Map.of("fullName", "Ada Lovelace"), true);

        try (PDDocument doc = Loader.loadPDF(out)) {
            PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
            // Flatten removes the fields (form becomes empty or gone), baking them into page content.
            assertTrue(form == null || form.getFields().isEmpty(), "fields should be flattened away");
            // The value is now real page text, and the signature image remains.
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("Ada Lovelace"), "flattened field value should be page text");
            assertTrue(hasImage(doc.getPage(0)), "signature image should survive flatten");
        }
    }

    @Test
    void fillsFieldWithNoPlacementsWhenNoForm() throws Exception {
        byte[] pdf = plainPdf(1);
        // No images, only field values against a PDF with no form: a clean no-op, valid PDF out.
        byte[] out = PdfSigner.executeBytes(pdf, List.of(), List.of(),
            Map.of("missing", "x"), false);
        try (PDDocument doc = Loader.loadPDF(out)) {
            assertEquals(1, doc.getNumberOfPages());
        }
    }

    @Test
    void rejectsPlacementOnMissingPage() throws Exception {
        byte[] pdf = plainPdf(1);
        byte[] sig = signaturePng();
        assertThrows(PdfOperationException.class, () -> PdfSigner.executeBytes(pdf, List.of(sig),
            List.of(new SignPlacement(0, 5, 10, 10, 50, 50)), Map.of(), false));
    }

    @Test
    void fitInsidePreservesAspectRatioForWideImageInSquareBox() {
        // A 2:1 image dropped into a 100x100 box must stay 2:1, not become 1:1 (no stretch).
        float[] fit = PdfSigner.fitInside(200, 100, 10, 20, 100, 100);
        float drawW = fit[2], drawH = fit[3];
        assertEquals(100f, drawW, 1e-4, "wide image should span the full box width");
        assertEquals(50f, drawH, 1e-4, "height is constrained to keep 2:1 aspect");
        assertEquals(drawW / drawH, 200f / 100f, 1e-4, "drawn region keeps the image aspect ratio");
        // Centred vertically within the box: (100-50)/2 = 25 above the box bottom.
        assertEquals(10f, fit[0], 1e-4, "no horizontal letterbox for a full-width fit");
        assertEquals(20f + 25f, fit[1], 1e-4, "letterboxed image is centred vertically");
    }

    @Test
    void fitInsidePreservesAspectRatioForTallImageInSquareBox() {
        // A 1:2 image into a 100x100 box stays 1:2 and is centred horizontally.
        float[] fit = PdfSigner.fitInside(100, 200, 0, 0, 100, 100);
        float drawW = fit[2], drawH = fit[3];
        assertEquals(50f, drawW, 1e-4);
        assertEquals(100f, drawH, 1e-4);
        assertEquals(drawW / drawH, 100f / 200f, 1e-4, "drawn region keeps the image aspect ratio");
        assertEquals(25f, fit[0], 1e-4, "letterboxed image is centred horizontally");
        assertEquals(0f, fit[1], 1e-4, "no vertical letterbox for a full-height fit");
    }

    // ------------------------------------------------------------------ helpers

    private static boolean hasImage(PDPage page) throws IOException {
        PDResources res = page.getResources();
        if (res == null) return false;
        for (COSName name : res.getXObjectNames()) {
            PDXObject xobj = res.getXObject(name);
            if (xobj instanceof PDImageXObject) return true;
        }
        return false;
    }

    /** A one-page PDF carrying an AcroForm with a single text field named {@code fullName}. */
    private static byte[] formPdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            PDAcroForm form = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(form);
            PDResources dr = new PDResources();
            dr.put(COSName.getPDFName("Helv"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            form.setDefaultResources(dr);
            form.setDefaultAppearance("/Helv 12 Tf 0 g");

            PDTextField field = new PDTextField(form);
            field.setPartialName("fullName");
            field.setDefaultAppearance("/Helv 12 Tf 0 g");
            PDAnnotationWidget widget = field.getWidgets().get(0);
            widget.setRectangle(new PDRectangle(100, 700, 200, 20));
            widget.setPage(page);
            page.getAnnotations().add(widget);
            form.getFields().add(field);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos);
            return bos.toByteArray();
        }
    }

    private static byte[] plainPdf(int pages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) doc.addPage(new PDPage(PDRectangle.A4));
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos);
            return bos.toByteArray();
        }
    }

    /** A transparent-background PNG standing in for a drawn/typed signature. */
    private static byte[] signaturePng() throws IOException {
        BufferedImage img = new BufferedImage(240, 80, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLUE);
        g.drawLine(10, 60, 230, 20);
        g.drawLine(10, 20, 230, 60);
        g.dispose();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }
}
