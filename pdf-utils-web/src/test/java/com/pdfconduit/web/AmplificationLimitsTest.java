package com.pdfconduit.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Resource-<em>amplification</em> ceilings: the cases where one small, perfectly free-tier-legal
 * request buys arbitrarily much work because a limit is applied to the request's <em>inputs</em>
 * while the cost is driven by something else entirely.
 *
 * <ul>
 *   <li><b>Arrange</b> — the order expression duplicates pages on repeats, so {@code 1,1,1,…} turns
 *       a one-page upload into an arbitrarily large document. The input page count is bounded; the
 *       <em>result</em> page count is the one that costs memory, and it must be bounded too — on
 *       {@code /api/arrange} and on the pipeline's {@code ARRANGE} node alike.</li>
 *   <li><b>Duplicated pipeline sources</b> — a source node may list the same uploaded name many
 *       times, so one upload can be processed N times while the multipart part count (what the
 *       quota, the file cap and the load guard's byte reservation all see) stays at one.</li>
 *   <li><b>Graph size</b> — nothing bounded {@code nodes}/{@code connections}, so a single request
 *       could run thousands of core operations under one load-guard permit.</li>
 *   <li><b>Error parity</b> — a bad page range inside a pipeline must be the client's 400
 *       {@code invalid_page_range}, exactly as on the single-operation endpoints, not a 422 that
 *       reads like the server failed.</li>
 * </ul>
 */
@SpringBootTest(properties = {
    "pdfconduit.web.ratelimit.enabled=false",
    "pdfconduit.web.quota.enabled=false",
    "pdfconduit.web.ocr.enabled=false",
    "pdfconduit.web.pdf.max-pages=3",
    "pdfconduit.web.max-files-per-request=4",
    "pdfconduit.web.pipeline.max-nodes=4",
    "pdfconduit.web.pipeline.max-connections=4"
})
@AutoConfigureMockMvc
class AmplificationLimitsTest {

    @Autowired
    private MockMvc mvc;

    private static MockMultipartFile pdf(byte[] bytes) {
        return new MockMultipartFile("files", "a.pdf", "application/pdf", bytes);
    }

    private static MockMultipartFile single(byte[] bytes) {
        return new MockMultipartFile("file", "a.pdf", "application/pdf", bytes);
    }

    private static String source(String id, String... files) {
        StringBuilder sb = new StringBuilder();
        for (String f : files) {
            if (sb.length() > 0) sb.append(',');
            sb.append('"').append(f).append('"');
        }
        return "{\"id\":\"" + id + "\",\"kind\":\"SOURCE\",\"x\":0,\"y\":0,\"files\":[" + sb + "]}";
    }

    private static String model(String nodes, String connections) {
        return "{\"nodes\":[" + nodes + "],\"connections\":[" + connections + "]}";
    }

    private static String edge(String from, String to) {
        return "{\"fromNodeId\":\"" + from + "\",\"toNodeId\":\"" + to + "\"}";
    }

    /** An order expression repeating page 1 {@code times} over — the amplification payload. */
    private static String repeatedOrder(int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) {
            if (i > 0) sb.append(',');
            sb.append('1');
        }
        return sb.toString();
    }

    // -------------------------------------------------- arrange: result page count

    /**
     * One page in, {@code pdf.max-pages}+ pages out. The upload is tiny and every input-side limit
     * is satisfied, so only a ceiling on the RESULT can stop it.
     */
    @Test
    void arrangeOrderExpandingPastPageCap_returns422() throws Exception {
        mvc.perform(multipart("/api/arrange").file(single(TestPdfs.blank(1)))
                .param("order", repeatedOrder(50)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("operation_failed"))
            .andExpect(jsonPath("$.error", containsString("maximum page count")));
    }

    /** The pipeline's ARRANGE node reaches the same core operation, so it must answer identically. */
    @Test
    void arrangePipelineNodeExpandingPastPageCap_returns422() throws Exception {
        String json = model(
            source("s", "a.pdf") + ",{\"id\":\"a\",\"kind\":\"ARRANGE\",\"x\":0,\"y\":0,"
                + "\"order\":\"" + repeatedOrder(50) + "\"}",
            edge("s", "a"));

        mvc.perform(multipart("/api/pipeline/run").file(pdf(TestPdfs.blank(1)))
                .param("pipeline", json))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("operation_failed"))
            .andExpect(jsonPath("$.error", containsString("maximum page count")));
    }

    /** A real reorder is still a reorder: the guard rejects the abuse, not the feature. */
    @Test
    void normalArrange_returns200() throws Exception {
        mvc.perform(multipart("/api/arrange").file(single(TestPdfs.blank(2)))
                .param("order", "2,1"))
            .andExpect(status().isOk());
    }

    /** …and the same holds for the ARRANGE node inside a pipeline. */
    @Test
    void normalArrangePipelineNode_returns200() throws Exception {
        String json = model(
            source("s", "a.pdf") + ",{\"id\":\"a\",\"kind\":\"ARRANGE\",\"x\":0,\"y\":0,"
                + "\"order\":\"2,1\"}",
            edge("s", "a"));

        mvc.perform(multipart("/api/pipeline/run").file(pdf(TestPdfs.blank(2)))
                .param("pipeline", json))
            .andExpect(status().isOk());
    }

    // ------------------------------------------------ duplicated pipeline sources

    /**
     * One uploaded part, referenced far more often than the per-request file ceiling allows: the
     * work is per <em>reference</em>, so references are what must be counted.
     */
    @Test
    void pipelineDuplicateSourceReferences_returns400() throws Exception {
        String json = model(
            source("s", "a.pdf", "a.pdf", "a.pdf", "a.pdf", "a.pdf", "a.pdf")
                + ",{\"id\":\"r\",\"kind\":\"ROTATE\",\"x\":0,\"y\":0,\"pages\":\"\",\"angle\":90}",
            edge("s", "r"));

        mvc.perform(multipart("/api/pipeline/run").file(pdf(TestPdfs.blank(1)))
                .param("pipeline", json))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("bad_request"))
            .andExpect(jsonPath("$.error", containsString("source documents")));
    }

    /** Duplicate references within the cap remain legal — repeating a file is a valid pipeline. */
    @Test
    void pipelineDuplicateSourceReferencesWithinCap_returns200() throws Exception {
        String json = model(
            source("s", "a.pdf", "a.pdf")
                + ",{\"id\":\"r\",\"kind\":\"ROTATE\",\"x\":0,\"y\":0,\"pages\":\"\",\"angle\":90}",
            edge("s", "r"));

        mvc.perform(multipart("/api/pipeline/run").file(pdf(TestPdfs.blank(1)))
                .param("pipeline", json))
            .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ graph size

    /** A graph past the node cap is refused before a single operation runs. */
    @Test
    void pipelineOverNodeCap_run_returns400() throws Exception {
        mvc.perform(multipart("/api/pipeline/run").file(pdf(TestPdfs.blank(1)))
                .param("pipeline", oversizedGraph()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("bad_request"))
            .andExpect(jsonPath("$.error", containsString("too many nodes")));
    }

    /** …and the builder learns the same thing from /validate, without uploading anything. */
    @Test
    void pipelineOverNodeCap_validate_returns400() throws Exception {
        mvc.perform(multipart("/api/pipeline/validate").param("pipeline", oversizedGraph()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("bad_request"))
            .andExpect(jsonPath("$.error", containsString("too many nodes")));
    }

    /** A graph within the caps still validates normally (200 + the usual error list). */
    @Test
    void pipelineWithinNodeCap_validate_returns200() throws Exception {
        String json = model(
            source("s", "a.pdf") + ",{\"id\":\"r\",\"kind\":\"ROTATE\",\"x\":0,\"y\":0,"
                + "\"pages\":\"\",\"angle\":90}",
            edge("s", "r"));

        mvc.perform(multipart("/api/pipeline/validate").param("pipeline", json))
            .andExpect(status().isOk());
    }

    /** Five nodes against a cap of four (one source + four rotates chained). */
    private static String oversizedGraph() {
        StringBuilder nodes = new StringBuilder(source("s", "a.pdf"));
        StringBuilder edges = new StringBuilder();
        String prev = "s";
        for (int i = 0; i < 4; i++) {
            String id = "r" + i;
            nodes.append(",{\"id\":\"").append(id).append("\",\"kind\":\"ROTATE\",\"x\":0,\"y\":0,")
                 .append("\"pages\":\"\",\"angle\":90}");
            if (edges.length() > 0) edges.append(',');
            edges.append(edge(prev, id));
            prev = id;
        }
        return model(nodes.toString(), edges.toString());
    }

    // ------------------------------------------------------------- error-code parity

    /** A bad page range is the client's mistake wherever it is written — 400, not 422. */
    @Test
    void pipelineBadPageRange_returns400InvalidPageRange() throws Exception {
        String json = model(
            source("s", "a.pdf") + ",{\"id\":\"r\",\"kind\":\"ROTATE\",\"x\":0,\"y\":0,"
                + "\"pages\":\"abc\",\"angle\":90}",
            edge("s", "r"));

        mvc.perform(multipart("/api/pipeline/run").file(pdf(TestPdfs.blank(1)))
                .param("pipeline", json))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("invalid_page_range"));
    }

    /** The same parity for an ARRANGE order expression, which parses through the order parser. */
    @Test
    void pipelineBadArrangeOrder_returns400InvalidPageRange() throws Exception {
        String json = model(
            source("s", "a.pdf") + ",{\"id\":\"a\",\"kind\":\"ARRANGE\",\"x\":0,\"y\":0,"
                + "\"order\":\"abc\"}",
            edge("s", "a"));

        mvc.perform(multipart("/api/pipeline/run").file(pdf(TestPdfs.blank(1)))
                .param("pipeline", json))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("invalid_page_range"));
    }
}
