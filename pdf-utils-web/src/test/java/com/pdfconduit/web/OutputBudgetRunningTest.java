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
 * The <b>running</b> half of the output budget: {@code processing.max-total-output-bytes} is
 * enforced while the parts accumulate, not after the fact, on every path that materialises a
 * {@code List<byte[]>} (to-images and extract?separate=true). The estimate-based pixel ceiling
 * cannot see how well a page will compress, so this is the guard that actually bounds heap.
 *
 * <p>The pixel ceiling is raised out of the way here so a 422 can only have come from the byte
 * ceiling, and the byte ceiling is set absurdly low so the abort is provably not a coincidence of
 * input size. It is still a clean 422 {@code output_too_large} — never an OOM, never a 500.
 */
@SpringBootTest(properties = {
    "pdfconduit.web.ratelimit.enabled=false",
    "pdfconduit.web.quota.enabled=false",
    "pdfconduit.web.render.max-total-output-pixels=100000000000",
    "pdfconduit.web.processing.max-total-output-bytes=2KB"
})
@AutoConfigureMockMvc
class OutputBudgetRunningTest {

    @Autowired
    private MockMvc mvc;

    private static MockMultipartFile file(byte[] bytes, String name) {
        return new MockMultipartFile("files", name, "application/pdf", bytes);
    }

    @Test
    void toImages_accumulatedBytesOverBudget_rejectedAsOutputTooLarge() throws Exception {
        mvc.perform(multipart("/api/to-images").file(file(TestPdfs.blank(40), "a.pdf"))
                .param("dpi", "150"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("output_too_large"));
    }

    @Test
    void extractSeparate_accumulatedBytesOverBudget_rejectedAsOutputTooLarge() throws Exception {
        mvc.perform(multipart("/api/extract").file(file(TestPdfs.blank(40), "a.pdf"))
                .param("separate", "true"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("output_too_large"));
    }

    /** The budget is per REQUEST: several files that each fit must still be summed together. */
    @Test
    void extractSeparate_budgetIsSharedAcrossFiles() throws Exception {
        byte[] one = TestPdfs.blank(1);
        // A single 1-page split fits inside 2 KB...
        mvc.perform(multipart("/api/extract").file(file(one, "a.pdf")).param("separate", "true"))
            .andExpect(status().isOk());
        // ...but enough of them do not, even though no individual file changed.
        mvc.perform(multipart("/api/extract")
                .file(file(one, "a.pdf")).file(file(one, "b.pdf")).file(file(one, "c.pdf"))
                .file(file(one, "d.pdf")).file(file(one, "e.pdf")).file(file(one, "f.pdf"))
                .param("separate", "true"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("output_too_large"));
    }
}
