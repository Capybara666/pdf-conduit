package com.pdfconduit.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The <b>product of dimensions</b> hole: eight uploads of ~19 MB (each under the per-file cap, the
 * count under the file cap) through a 13-node ROTATE chain (under the graph-size cap). Every
 * individual ceiling is satisfied, and yet the run costs eight times nineteen megabytes times
 * thirteen retained stages, because {@code PipelineExecutor} keeps every node's outputs alive for
 * the whole run.
 *
 * <p>What makes this a <em>cost</em> test rather than another status-code test: the request was
 * already refused before, but only by the output budget checking the <em>assembled result</em> —
 * after the executor had allocated every stage. The fix is that the cost is worked out from the
 * graph and the resolved source bytes <b>before the run starts</b>, so the refusal names memory and
 * arrives in milliseconds instead of after gigabytes have been allocated. Both properties are
 * asserted: the message, and an elapsed time orders of magnitude below what running the graph costs.
 */
@SpringBootTest(properties = {
    "pdfconduit.web.ratelimit.enabled=false",
    "pdfconduit.web.quota.enabled=false",
    "pdfconduit.web.ocr.enabled=false"
})
@AutoConfigureMockMvc
class PipelineCostLimitsTest {

    /** Eight uploads of ~19 MB: comfortably inside the 25 MB per-file and 15-file free-tier caps. */
    private static final int FILES = 8;
    private static final int FILE_BYTES = 19 * 1024 * 1024;

    /** Thirteen chained ROTATE nodes plus the source: well inside the 50-node graph ceiling. */
    private static final int STAGES = 13;

    /**
     * Admission must be decided from the estimate, so no PDF is parsed and nothing is produced.
     * Running the graph for real takes tens of seconds and allocates gigabytes; two seconds is far
     * below that and far above what a pre-flight arithmetic check needs.
     */
    private static final long ADMISSION_BUDGET_MS = 2_000;

    @Autowired
    private MockMvc mvc;

    @Test
    void eightLargeUploadsThroughA13StageChain_refusedBeforeAnythingIsAllocated() throws Exception {
        byte[] pdf = TestPdfs.bulky(FILE_BYTES);
        MockMultipartHttpServletRequestBuilder request = multipart("/api/pipeline/run");
        for (int i = 0; i < FILES; i++) {
            request.file(new MockMultipartFile("files", "f" + i + ".pdf", "application/pdf", pdf));
        }
        request.param("pipeline", rotateChain(FILES, STAGES));

        long started = System.nanoTime();
        mvc.perform(request)
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("output_too_large"))
            // The refusal must come from the memory estimate, not from measuring the finished
            // result — the whole point is that the result is never built.
            .andExpect(jsonPath("$.error", containsString("MB of memory")));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertTrue(elapsedMs < ADMISSION_BUDGET_MS,
            "the run must be refused from its estimate, before any stage is executed; took "
                + elapsedMs + " ms");
    }

    /** A pipeline whose cost genuinely fits still runs: the model refuses abuse, not the feature. */
    @Test
    void modestPipeline_stillRuns() throws Exception {
        mvc.perform(multipart("/api/pipeline/run")
                .file(new MockMultipartFile("files", "a.pdf", "application/pdf", TestPdfs.blank(2)))
                .param("pipeline", rotateChain(1, 3)))
            .andExpect(status().isOk());
    }

    /** {@code files} source names + {@code stages} chained ROTATE nodes. */
    private static String rotateChain(int files, int stages) {
        StringBuilder sources = new StringBuilder();
        for (int i = 0; i < files; i++) {
            if (i > 0) sources.append(',');
            sources.append('"').append(files == 1 ? "a.pdf" : "f" + i + ".pdf").append('"');
        }
        StringBuilder nodes = new StringBuilder(
            "{\"id\":\"s\",\"kind\":\"SOURCE\",\"x\":0,\"y\":0,\"files\":[" + sources + "]}");
        StringBuilder edges = new StringBuilder();
        String prev = "s";
        for (int i = 0; i < stages; i++) {
            String id = "r" + i;
            nodes.append(",{\"id\":\"").append(id).append("\",\"kind\":\"ROTATE\",\"x\":0,\"y\":0,")
                 .append("\"pages\":\"\",\"angle\":90}");
            if (edges.length() > 0) edges.append(',');
            edges.append("{\"fromNodeId\":\"").append(prev).append("\",\"toNodeId\":\"")
                 .append(id).append("\"}");
            prev = id;
        }
        return "{\"nodes\":[" + nodes + "],\"connections\":[" + edges + "]}";
    }
}
