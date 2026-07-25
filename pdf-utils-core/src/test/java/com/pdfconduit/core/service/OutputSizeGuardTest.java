package com.pdfconduit.core.service;

import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.ImageFormat;
import com.pdfconduit.core.model.PageRange;
import com.pdfconduit.core.operations.PdfSplitter;
import com.pdfconduit.core.operations.PdfToImageConverter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The multi-output operations must report their accumulated size to an {@link OutputSizeGuard}
 * <b>per produced part</b> and abort the moment it throws. That is what lets a caller with a
 * memory budget (the web backend) refuse a runaway render/split early instead of holding every
 * page in the heap and dying on the last one — so "it aborts at the end" is not good enough, and
 * these tests count the callbacks to prove it stops mid-run.
 */
class OutputSizeGuardTest {

    /** Records every accumulated total the operation reports; optionally aborts at page {@code failAt}. */
    private static final class Recorder implements OutputSizeGuard {
        final List<Long> totals = new ArrayList<>();
        private final int failAt;

        Recorder(int failAt) {
            this.failAt = failAt;
        }

        @Override
        public void check(long accumulatedBytes) throws PdfOperationException {
            totals.add(accumulatedBytes);
            if (failAt > 0 && totals.size() >= failAt) {
                throw new PdfOperationException("budget blown");
            }
        }
    }

    private static byte[] pdf(int pages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) doc.addPage(new PDPage(PDRectangle.A4));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void toImages_reportsRunningTotalPerPage() throws Exception {
        Recorder rec = new Recorder(0);
        List<byte[]> images = PdfToImageConverter.executeBytes(pdf(4), ImageFormat.PNG, 36,
            PageRange.ALL, 1f, false, false, rec);

        assertEquals(4, images.size());
        assertEquals(4, rec.totals.size(), "the guard sees every page, not just the final result");
        long expected = 0;
        for (int i = 0; i < images.size(); i++) {
            expected += images.get(i).length;
            assertEquals(expected, rec.totals.get(i), "guard must see the accumulated size so far");
        }
    }

    @Test
    void toImages_abortsAtTheGuardsPage_notAtTheEnd() throws Exception {
        Recorder rec = new Recorder(2);
        assertThrows(PdfOperationException.class, () -> PdfToImageConverter.executeBytes(
            pdf(20), ImageFormat.PNG, 36, PageRange.ALL, 1f, false, false, rec));
        assertEquals(2, rec.totals.size(), "rendering must stop on the guard's page, not run all 20");
    }

    @Test
    void separateBytes_reportsRunningTotalAndAbortsEarly() throws Exception {
        Recorder counting = new Recorder(0);
        List<byte[]> parts = PdfSplitter.separateBytes(pdf(4), PageRange.ALL, counting);
        assertEquals(4, parts.size());
        assertEquals(4, counting.totals.size());
        assertTrue(counting.totals.get(3) > counting.totals.get(0), "totals accumulate");

        Recorder aborting = new Recorder(2);
        assertThrows(PdfOperationException.class,
            () -> PdfSplitter.separateBytes(pdf(20), PageRange.ALL, aborting));
        assertEquals(2, aborting.totals.size(), "splitting must stop on the guard's page");
    }

    @Test
    void nullGuard_leavesBehaviourUnchanged() throws Exception {
        byte[] src = pdf(3);
        assertEquals(PdfToImageConverter.executeBytes(src, ImageFormat.PNG, 36, PageRange.ALL, 1f).size(),
            PdfToImageConverter.executeBytes(src, ImageFormat.PNG, 36, PageRange.ALL, 1f,
                false, false, null).size());
        assertEquals(PdfSplitter.separateBytes(src, PageRange.ALL).size(),
            PdfSplitter.separateBytes(src, PageRange.ALL, null).size());
    }
}
