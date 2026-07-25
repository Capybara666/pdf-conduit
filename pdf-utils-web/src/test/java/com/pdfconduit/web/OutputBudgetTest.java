package com.pdfconduit.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The per-request AGGREGATE output budget — the guard the per-page render caps do not provide.
 *
 * <p>{@code render.max-output-pixels} only rejects one oversized page; on its own nothing bounds
 * {@code pages × files}. A 300 DPI render of a few hundred perfectly ordinary pages passes every
 * per-page guard and would then materialise one PNG per page, zip them into a
 * {@code ByteArrayOutputStream} and copy that again into the response body — over a gigabyte in a
 * ~1.15 GB container heap,
 * with {@code -XX:+ExitOnOutOfMemoryError} turning it into a container restart (and, with it, a
 * reset of every user's in-memory quota).
 *
 * <p>This class pins the aggregate ceilings with the SHIPPED strict defaults (500 MP / 64 MB): the
 * budget is checked before anything is rasterised, so these reject in milliseconds without
 * rendering a single page. {@link OutputBudgetRunningTest} pins the running byte ceiling.
 */
@SpringBootTest(properties = {
    "pdfconduit.web.ratelimit.enabled=false",
    "pdfconduit.web.quota.enabled=false"
})
@AutoConfigureMockMvc
class OutputBudgetTest {

    /** A4 at 300 DPI ≈ 8.7 MP, so 60 pages ≈ 522 MP — just over the 500 MP default. */
    private static final int PAGES_OVER_BUDGET = 60;
    /** 25 pages ≈ 218 MP: comfortably inside the budget on its own. */
    private static final int PAGES_UNDER_BUDGET = 25;

    @Autowired
    private MockMvc mvc;

    private static MockMultipartFile file(byte[] bytes, String name) {
        return new MockMultipartFile("files", name, "application/pdf", bytes);
    }

    /** The reported attack shape: one many-page scan at the maximum allowed DPI. */
    @Test
    void toImages_manyPagesAtMaxDpi_rejectedAsOutputTooLarge() throws Exception {
        byte[] many = TestPdfs.blank(600);   // ≈ 5200 MP at 300 DPI — ~1 GB of PNGs
        mvc.perform(multipart("/api/to-images").file(file(many, "big.pdf"))
                .param("dpi", "300"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("output_too_large"));
    }

    /** Just over the ceiling with one file — the boundary, not just an absurd input. */
    @Test
    void toImages_singleFileJustOverPixelBudget_rejected() throws Exception {
        mvc.perform(multipart("/api/to-images").file(file(TestPdfs.blank(PAGES_OVER_BUDGET), "a.pdf"))
                .param("dpi", "300"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("output_too_large"));
    }

    /**
     * The case that matters: three files that each pass every per-file guard, but whose SUM does
     * not. Per-file clamps alone accept this request and render all three.
     */
    @Test
    void toImages_aggregateOverFiles_rejectedEvenThoughEachFilePasses() throws Exception {
        byte[] pdf = TestPdfs.blank(PAGES_UNDER_BUDGET);
        mvc.perform(multipart("/api/to-images")
                .file(file(pdf, "a.pdf")).file(file(pdf, "b.pdf")).file(file(pdf, "c.pdf"))
                .param("dpi", "300"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("output_too_large"));
    }

    /**
     * The other half of that proof: ONE of those same files, at the SAME 300 DPI, is still
     * accepted and rendered. So the rejection above really comes from the sum across files —
     * nothing tightened for an individual file.
     */
    @Test
    void toImages_singleFileOfTheRejectedAggregate_stillSucceeds() throws Exception {
        mvc.perform(multipart("/api/to-images").file(file(TestPdfs.blank(PAGES_UNDER_BUDGET), "a.pdf"))
                .param("dpi", "300"))
            .andExpect(status().isOk());
    }

    /** An ordinary small request is untouched by the aggregate ceilings. */
    @Test
    void toImages_smallRequest_stillSucceeds() throws Exception {
        mvc.perform(multipart("/api/to-images").file(file(TestPdfs.blank(2), "a.pdf"))
                .param("dpi", "150"))
            .andExpect(status().isOk());
    }

    /** The single-page render endpoint counts only the page it renders, so it stays unaffected. */
    @Test
    void render_singlePageOfALongDocument_stillSucceeds() throws Exception {
        mvc.perform(multipart("/api/render")
                .file(new MockMultipartFile("file", "a.pdf", "application/pdf",
                    TestPdfs.blank(PAGES_OVER_BUDGET)))
                .param("page", "0")
                .param("dpi", "150"))
            .andExpect(status().isOk());
    }
}
