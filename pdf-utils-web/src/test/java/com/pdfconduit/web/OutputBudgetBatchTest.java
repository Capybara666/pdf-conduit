package com.pdfconduit.web;

import com.pdfconduit.core.operations.PdfProtector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.io.IOException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The aggregate output budget on the <b>one-output-per-file</b> MAP batches — {@code /api/extract}
 * in combine mode and its siblings (rotate, compress, watermark, metadata, …).
 *
 * <p>These accumulate exactly like the multi-output paths ({@code to-images},
 * {@code extract?separate}): a 200 MB upload turns into ~200 MB of results, held in the heap and
 * copied again into an in-memory ZIP, which the multipart ceiling and the free-tier caps do not
 * bound. They therefore run under exactly the same running ceiling — 422
 * {@code output_too_large}, never an OOM.
 *
 * <p>The byte ceiling is set absurdly low so an abort is provably the budget and not a coincidence
 * of input size, and the pixel ceiling is raised out of the way so a 422 can only have come from the
 * byte ceiling.
 */
@SpringBootTest(properties = {
    "pdfconduit.web.ratelimit.enabled=false",
    "pdfconduit.web.quota.enabled=false",
    "pdfconduit.web.render.max-total-output-pixels=100000000000",
    "pdfconduit.web.processing.max-total-output-bytes=2KB"
})
@AutoConfigureMockMvc
class OutputBudgetBatchTest {

    /** Comfortably more 1-page PDFs than fit in a 2 KB result budget. */
    private static final int MANY = 12;

    @Autowired
    private MockMvc mvc;

    private static MockMultipartFile pdf(String name, byte[] bytes) {
        return new MockMultipartFile("files", name, "application/pdf", bytes);
    }

    /** A PDF nobody can open without the password — the per-FILE failure this must keep tolerating. */
    private static byte[] encrypted() throws Exception {
        return PdfProtector.executeBytes(TestPdfs.blank(1), "secret", null);
    }

    /** {@code count} identical one-page PDFs posted to {@code path}, plus any extra parts. */
    private static MockMultipartHttpServletRequestBuilder batch(String path, int count,
                                                                MockMultipartFile... extra)
            throws IOException {
        MockMultipartHttpServletRequestBuilder request = multipart(path);
        for (MockMultipartFile f : extra) request.file(f);
        byte[] one = TestPdfs.blank(1);
        for (int i = 0; i < count; i++) request.file(pdf("f" + i + ".pdf", one));
        return request;
    }

    // ------------------------------------------------- one output per input file

    /** Combine-mode extract: one PDF per input, accumulated and zipped — bounded like the rest. */
    @Test
    void extractCombine_accumulatedBytesOverBudget_rejectedAsOutputTooLarge() throws Exception {
        mvc.perform(batch("/api/extract", MANY))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("output_too_large"));
    }

    /** The same for a plain one-output-per-file MAP operation. */
    @Test
    void rotate_accumulatedBytesOverBudget_rejectedAsOutputTooLarge() throws Exception {
        mvc.perform(batch("/api/rotate", MANY).param("angle", "90"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("output_too_large"));
    }

    /** It is a REQUEST ceiling: one file of that same batch is still processed and returned. */
    @Test
    void singleFileOfTheRejectedBatch_stillSucceeds() throws Exception {
        mvc.perform(batch("/api/rotate", 1).param("angle", "90"))
            .andExpect(status().isOk());
    }

    // ---------------------------------------------- partial tolerance is not weakened

    /**
     * A per-FILE defect stays a per-file entry: the good file comes back and the unusable one is
     * named in {@code X-Batch-Failures}. The budget wrapper commits only what actually succeeded,
     * so it neither hides a bad file nor is tripped by one.
     */
    @Test
    void oneBadFileUnderBudget_isStillTolerated_andNamed() throws Exception {
        mvc.perform(batch("/api/rotate", 1, pdf("locked.pdf", encrypted())).param("angle", "90"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Batch-Failures",
                org.hamcrest.Matchers.startsWith("locked.pdf: ")));
    }

    /**
     * A blown REQUEST budget is the opposite: it describes the batch as a whole, so it must fail the
     * whole request (422) rather than come back as a partial ZIP blaming whichever innocent file
     * happened to tip it over — that is what {@code BatchFatal} buys, and it must survive a batch
     * that ALSO contains a genuinely bad file.
     */
    @Test
    void blownBudget_failsTheWholeRequest_evenAlongsideAPerFileFailure() throws Exception {
        mvc.perform(batch("/api/rotate", MANY, pdf("locked.pdf", encrypted())).param("angle", "90"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("output_too_large"))
            // Not a partial ZIP, and not the per-file failure header.
            .andExpect(header().doesNotExist("X-Batch-Failures"));
    }
}
