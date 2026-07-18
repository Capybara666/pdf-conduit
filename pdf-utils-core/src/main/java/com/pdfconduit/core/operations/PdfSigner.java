package com.pdfconduit.core.operations;

import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.SignOptions;
import com.pdfconduit.core.model.SignPlacement;
import com.pdfconduit.core.model.SignResult;
import com.pdfconduit.core.util.OutputPaths;
import com.pdfconduit.core.util.PdfLoader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.util.Matrix;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fill &amp; Sign — Phase 1 (visual, PDFBox-only, no cryptographic signing). Three composable steps:
 * <ol>
 *   <li>fill AcroForm fields (text fields and checkboxes) when the PDF has a form,</li>
 *   <li>stamp signature images at chosen positions/pages (drawn/typed/uploaded upstream), and</li>
 *   <li>optionally flatten the form so its fields become fixed page content.</li>
 * </ol>
 *
 * <p>Placements arrive in the same coordinate space as {@link com.pdfconduit.core.model.RedactRegion}
 * — displayed-page points, top-left origin, 0-based page, rotation already applied by the viewer. The
 * signer maps that box into the page's un-rotated user space (bottom-left origin) with a per-rotation
 * affine transform, so a signature lands where the user drew it on rotated pages too. Images keep their
 * alpha (transparent-background PNG signatures composite cleanly). Stateless and thread-safe.
 *
 * <p>This is <b>not</b> a digital signature: it adds no PKCS#7 / cryptographic guarantee, only a visual
 * mark plus filled fields. Cryptographic signing is a deferred Phase 2.
 */
public final class PdfSigner {

    private PdfSigner() {}

    public static SignResult execute(SignOptions opts) throws PdfOperationException {
        try (PDDocument doc = PdfLoader.load(opts.input())) {
            List<BufferedImage> images = readImages(opts.images());
            Counts counts = sign(doc, images, opts.placements(), opts.fieldValues(), opts.flatten());
            OutputPaths.ensureParentDir(opts.output());
            doc.save(opts.output().toFile());
            return new SignResult(opts.output(), doc.getNumberOfPages(),
                counts.placements(), counts.fields());
        } catch (IOException e) {
            throw new PdfOperationException("Sign failed: " + e.getMessage(), e);
        }
    }

    /**
     * In-memory variant: fill fields, stamp {@code signatureImages} at {@code placements}, optionally
     * flatten, and return the new PDF bytes. {@code signatureImages}/{@code placements}/{@code fieldValues}
     * may be null or empty.
     */
    public static byte[] executeBytes(byte[] pdf, List<byte[]> signatureImages,
                                      List<SignPlacement> placements, Map<String, String> fieldValues,
                                      boolean flatten) throws PdfOperationException {
        try (PDDocument doc = PdfLoader.load(pdf)) {
            List<BufferedImage> images = readImageBytes(signatureImages);
            sign(doc, images, placements, fieldValues, flatten);
            return PdfLoader.toBytes(doc);
        } catch (IOException e) {
            throw new PdfOperationException("Sign failed: " + e.getMessage(), e);
        }
    }

    /** Placements stamped and fields filled, in one pass. */
    private record Counts(int placements, int fields) {}

    /** The shared algorithm: fill fields, stamp signatures, then (optionally) flatten the form. */
    private static Counts sign(PDDocument doc, List<BufferedImage> images, List<SignPlacement> placements,
                               Map<String, String> fieldValues, boolean flatten)
            throws IOException, PdfOperationException {
        int fieldsFilled = fillFields(doc, fieldValues);
        int stamped = stampSignatures(doc, images, placements);
        if (flatten) flattenForm(doc);
        return new Counts(stamped, fieldsFilled);
    }

    // ------------------------------------------------------------------ fields

    /** Fills AcroForm text fields / checkboxes by fully-qualified name; returns how many were set. */
    private static int fillFields(PDDocument doc, Map<String, String> fieldValues) throws IOException {
        if (fieldValues == null || fieldValues.isEmpty()) return 0;
        PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
        if (form == null) return 0;
        // Let PDFBox regenerate field appearances from the values we set (so they render everywhere).
        form.setNeedAppearances(false);
        int filled = 0;
        for (Map.Entry<String, String> e : fieldValues.entrySet()) {
            PDField field = form.getField(e.getKey());
            if (field == null) continue;
            String value = e.getValue() == null ? "" : e.getValue();
            if (field instanceof PDCheckBox check) {
                if (isChecked(value)) check.check(); else check.unCheck();
            } else {
                field.setValue(value);
            }
            filled++;
        }
        return filled;
    }

    /** Truthy strings that tick a checkbox; anything else (incl. blank / "off") unticks it. */
    private static boolean isChecked(String value) {
        String v = value.trim().toLowerCase(java.util.Locale.ROOT);
        return Set.of("true", "yes", "on", "1", "x", "checked").contains(v);
    }

    /** Flattens the AcroForm so every field becomes static page content (fields removed). */
    private static void flattenForm(PDDocument doc) throws IOException {
        PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
        if (form != null && !form.getFields().isEmpty()) {
            form.flatten();
        }
    }

    // -------------------------------------------------------------- signatures

    /** Draws each placement's signature image onto its page; returns how many were stamped. */
    private static int stampSignatures(PDDocument doc, List<BufferedImage> images,
                                       List<SignPlacement> placements)
            throws IOException, PdfOperationException {
        if (placements == null || placements.isEmpty()) return 0;
        int total = doc.getNumberOfPages();

        // Build one PDImageXObject per source image, reused across placements (alpha preserved).
        List<PDImageXObject> xobjects = new ArrayList<>(images.size());
        for (BufferedImage bi : images) xobjects.add(LosslessFactory.createFromImage(doc, bi));

        int stamped = 0;
        for (SignPlacement p : placements) {
            if (p.width() <= 0 || p.height() <= 0) continue;
            if (p.pageIndex() < 0 || p.pageIndex() >= total) {
                throw new PdfOperationException("Signature placement references page "
                    + (p.pageIndex() + 1) + ", but the PDF has " + total + " page(s).");
            }
            if (p.imageIndex() < 0 || p.imageIndex() >= xobjects.size()) {
                throw new PdfOperationException("Signature placement references image "
                    + p.imageIndex() + ", but " + xobjects.size() + " image(s) were supplied.");
            }
            PDPage page = doc.getPage(p.pageIndex());
            drawPlacement(doc, page, xobjects.get(p.imageIndex()), p);
            stamped++;
        }
        return stamped;
    }

    /**
     * Stamps one image at {@code p}. The content stream's coordinate system is transformed so that,
     * after the transform, drawing in <em>displayed</em> space (top-left origin as the user sees the
     * page) produces the right result on the page's un-rotated user space — including 90/180/270°
     * rotated pages. The transform is rotation-only (no scale), so the image aspect is exactly the
     * placement box the user drew.
     */
    private static void drawPlacement(PDDocument doc, PDPage page, PDImageXObject img, SignPlacement p)
            throws IOException {
        PDRectangle box = page.getCropBox();
        float ox = box.getLowerLeftX(), oy = box.getLowerLeftY();
        float w = box.getWidth(), h = box.getHeight();
        int rot = ((page.getRotation() % 360) + 360) % 360;

        // Rotation-aware displayed height (points), used to flip the top-left y into a bottom-up y.
        float displayedH = (rot == 90 || rot == 270) ? w : h;

        // Affine mapping displayed-bottom-left points -> un-rotated user space (cropbox-relative),
        // then shifted by the cropbox origin. Matrix(a,b,c,d,e,f): (u,v) -> (a*u+c*v+e, b*u+d*v+f).
        Matrix m = switch (rot) {
            case 90  -> new Matrix(0, 1, -1, 0, ox + w, oy);
            case 180 -> new Matrix(-1, 0, 0, -1, ox + w, oy + h);
            case 270 -> new Matrix(0, -1, 1, 0, ox, oy + h);
            default  -> new Matrix(1, 0, 0, 1, ox, oy);
        };

        // Placement box in displayed-bottom-left coords (flip the top-left y).
        float left = (float) p.x();
        float bottom = displayedH - (float) p.y() - (float) p.height();
        float dw = (float) p.width();
        float dh = (float) p.height();

        try (PDPageContentStream cs =
                 new PDPageContentStream(doc, page, AppendMode.APPEND, true, true)) {
            cs.saveGraphicsState();
            cs.transform(m);
            cs.drawImage(img, left, bottom, dw, dh);
            cs.restoreGraphicsState();
        }
    }

    // ------------------------------------------------------------------ images

    private static List<BufferedImage> readImages(List<java.nio.file.Path> paths)
            throws PdfOperationException, IOException {
        List<BufferedImage> out = new ArrayList<>();
        if (paths == null) return out;
        for (java.nio.file.Path path : paths) {
            BufferedImage bi = ImageIO.read(path.toFile());
            if (bi == null) {
                throw new PdfOperationException("Cannot read signature image: " + path.getFileName());
            }
            out.add(bi);
        }
        return out;
    }

    private static List<BufferedImage> readImageBytes(List<byte[]> images)
            throws PdfOperationException, IOException {
        List<BufferedImage> out = new ArrayList<>();
        if (images == null) return out;
        for (byte[] data : images) {
            BufferedImage bi = ImageIO.read(new ByteArrayInputStream(data));
            if (bi == null) {
                throw new PdfOperationException(
                    "Cannot read signature image: unsupported or corrupt image data.");
            }
            out.add(bi);
        }
        return out;
    }
}
