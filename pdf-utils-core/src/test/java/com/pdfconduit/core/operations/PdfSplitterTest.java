package com.pdfconduit.core.operations;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import com.pdfconduit.core.model.PageRange;
import com.pdfconduit.core.model.SplitMode;
import com.pdfconduit.core.model.SplitOptions;
import com.pdfconduit.core.model.SplitResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PdfSplitterTest {

    @TempDir Path tmp;

    @Test
    void extractsSinglePage() throws Exception {
        Path src = createPdf(5);
        Path out = tmp.resolve("split.pdf");

        SplitResult result = PdfSplitter.execute(
            new SplitOptions(src, new PageRange(List.of(2)), out));

        assertEquals(1, result.pageCount());
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(1, doc.getNumberOfPages());
        }
    }

    @Test
    void extractsRange() throws Exception {
        Path src = createPdf(10);
        Path out = tmp.resolve("range.pdf");

        SplitResult result = PdfSplitter.execute(
            new SplitOptions(src, new PageRange(List.of(2, 3, 4)), out));

        assertEquals(3, result.pageCount());
    }

    @Test
    void extractsAllWhenRangeIsAll() throws Exception {
        Path src = createPdf(4);
        Path out = tmp.resolve("all.pdf");

        SplitResult result = PdfSplitter.execute(
            new SplitOptions(src, PageRange.ALL, out));

        assertEquals(4, result.pageCount());
    }

    @Test
    void separateWritesOneFilePerSelectedPage() throws Exception {
        Path src = createPdf(3);
        Path dir = tmp.resolve("burst");

        SplitResult result = PdfSplitter.execute(
            new SplitOptions(src, PageRange.ALL, SplitMode.SEPARATE, dir));

        assertEquals(3, result.fileCount());
        assertEquals(3, result.pageCount());
        for (Path out : result.outputs()) {
            assertTrue(java.nio.file.Files.exists(out));
            try (PDDocument doc = Loader.loadPDF(out.toFile())) {
                assertEquals(1, doc.getNumberOfPages());
            }
        }
    }

    @Test
    void separateRespectsPageSelection() throws Exception {
        Path src = createPdf(5);
        Path dir = tmp.resolve("burst-sel");

        SplitResult result = PdfSplitter.execute(
            new SplitOptions(src, new PageRange(List.of(2, 4)), SplitMode.SEPARATE, dir));

        assertEquals(2, result.fileCount());
    }

    // ------------------------------------------------------- split every N pages

    @Test
    void splitsEveryNPagesWithAShorterLastPart() throws Exception {
        Path src = createPdf(7);
        Path dir = tmp.resolve("every-3");

        SplitResult result = PdfSplitter.execute(
            new SplitOptions(src, PageRange.ALL, SplitMode.SEPARATE, dir, 3));

        // 7 pages / 3 → 3 parts: 3 + 3 + 1 (the remainder is its own, shorter part).
        assertEquals(3, result.fileCount());
        assertEquals(7, result.pageCount());
        assertEquals(List.of(3, 3, 1), pageCounts(result));
        // Parts are named after the pages they span; a single-page part keeps the plain page name.
        assertEquals(List.of("src-7_p1-3.pdf", "src-7_p4-6.pdf", "src-7_p7.pdf"),
            result.outputs().stream().map(p -> p.getFileName().toString()).toList());
    }

    @Test
    void splitEveryOnePageIsTheHistoricPerPageSplit() throws Exception {
        Path src = createPdf(3);
        Path dir = tmp.resolve("every-1");

        SplitResult result = PdfSplitter.execute(
            new SplitOptions(src, PageRange.ALL, SplitMode.SEPARATE, dir, 1));

        assertEquals(3, result.fileCount());
        assertEquals(List.of(1, 1, 1), pageCounts(result));
        assertEquals(List.of("src-3_p1.pdf", "src-3_p2.pdf", "src-3_p3.pdf"),
            result.outputs().stream().map(p -> p.getFileName().toString()).toList());
    }

    @Test
    void splitEveryAtOrAboveThePageCountYieldsOnePart() throws Exception {
        Path src = createPdf(4);

        SplitResult exact = PdfSplitter.execute(
            new SplitOptions(src, PageRange.ALL, SplitMode.SEPARATE, tmp.resolve("every-4"), 4));
        SplitResult beyond = PdfSplitter.execute(
            new SplitOptions(src, PageRange.ALL, SplitMode.SEPARATE, tmp.resolve("every-99"), 99));

        assertEquals(List.of(4), pageCounts(exact));
        assertEquals(List.of(4), pageCounts(beyond));
    }

    @Test
    void splitEveryChunksWithinTheSelectedRange() throws Exception {
        Path src = createPdf(10);
        Path dir = tmp.resolve("every-range");

        // Selection = 5 pages, chunked 2 at a time → 2 + 2 + 1; pages outside the range never appear.
        SplitResult result = PdfSplitter.execute(new SplitOptions(
            src, new PageRange(List.of(2, 3, 4, 5, 6)), SplitMode.SEPARATE, dir, 2));

        assertEquals(List.of(2, 2, 1), pageCounts(result));
        assertEquals(5, result.pageCount());
    }

    @Test
    void splitEveryKeepsThePagesInSelectionOrder() throws Exception {
        // Page i is given a unique width so each output page can be traced back to its source page.
        Path src = createSizedPdf(6);
        Path dir = tmp.resolve("every-order");

        SplitResult result = PdfSplitter.execute(
            new SplitOptions(src, PageRange.ALL, SplitMode.SEPARATE, dir, 2));

        assertEquals(List.of(2, 2, 2), pageCounts(result));
        assertEquals(List.of(10, 20), widths(result.outputs().get(0)));
        assertEquals(List.of(30, 40), widths(result.outputs().get(1)));
        assertEquals(List.of(50, 60), widths(result.outputs().get(2)));
    }

    @Test
    void rejectsAPagesPerChunkBelowOne() throws Exception {
        Path src = createPdf(3);
        Path dir = tmp.resolve("every-0");

        assertThrows(IllegalArgumentException.class, () ->
            new SplitOptions(src, PageRange.ALL, SplitMode.SEPARATE, dir, 0));
        assertThrows(IllegalArgumentException.class, () ->
            new SplitOptions(src, PageRange.ALL, SplitMode.SEPARATE, dir, -2));
    }

    @Test
    void inMemorySplitEveryMatchesThePathApi() throws Exception {
        byte[] pdf = java.nio.file.Files.readAllBytes(createPdf(7));

        List<byte[]> parts = PdfSplitter.separateBytes(pdf, PageRange.ALL, 3);

        assertEquals(3, parts.size());
        assertEquals(List.of(3, 3, 1), parts.stream().map(PdfSplitterTest::pageCount).toList());
        assertThrows(IllegalArgumentException.class,
            () -> PdfSplitter.separateBytes(pdf, PageRange.ALL, 0));
    }

    // ------------------------------------------------------------------ helpers

    private static List<Integer> pageCounts(SplitResult result) {
        return result.outputs().stream().map(PdfSplitterTest::pageCount).toList();
    }

    private static int pageCount(Path pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return doc.getNumberOfPages();
        } catch (IOException e) {
            throw new AssertionError("cannot read " + pdf, e);
        }
    }

    private static int pageCount(byte[] pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return doc.getNumberOfPages();
        } catch (IOException e) {
            throw new AssertionError("cannot read PDF bytes", e);
        }
    }

    /** The media-box widths of a part's pages — the fingerprint written by {@link #createSizedPdf}. */
    private static List<Integer> widths(Path pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            List<Integer> widths = new java.util.ArrayList<>();
            for (PDPage page : doc.getPages()) widths.add(Math.round(page.getMediaBox().getWidth()));
            return widths;
        } catch (IOException e) {
            throw new AssertionError("cannot read " + pdf, e);
        }
    }

    private Path createPdf(int pages) throws IOException {
        Path path = tmp.resolve("src-" + pages + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) doc.addPage(new PDPage(PDRectangle.A4));
            doc.save(path.toFile());
        }
        return path;
    }

    /** A PDF whose page {@code i} (1-based) is {@code i * 10} points wide, so pages are traceable. */
    private Path createSizedPdf(int pages) throws IOException {
        Path path = tmp.resolve("sized-" + pages + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 1; i <= pages; i++) {
                doc.addPage(new PDPage(new PDRectangle(i * 10f, 100f)));
            }
            doc.save(path.toFile());
        }
        return path;
    }
}
