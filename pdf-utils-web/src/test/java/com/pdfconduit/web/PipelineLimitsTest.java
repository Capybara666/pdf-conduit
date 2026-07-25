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
 * {@code POST /api/pipeline/run} must enforce the very same per-request ceilings the equivalent
 * single-operation endpoints do. The pipeline JSON is entirely client-supplied — including every
 * node's render DPI and OCR settings — and it reaches the identical core operations, so a missing
 * guard here is a full bypass of {@code render.max-dpi}, {@code render.max-output-pixels},
 * {@code pdf.max-pages} and the OCR gate (a {@code TO_IMAGES} node at 1200 DPI allocates hundreds of
 * megabytes per page and can OOM-kill the JVM for every other user).
 *
 * <p>Crafted pipeline JSON must also never reach the generic 500 handler: unusable name references
 * are a client mistake (400), not an internal error plus a stack trace in the production log.
 */
@SpringBootTest(properties = {
    "pdfconduit.web.ratelimit.enabled=false",
    "pdfconduit.web.quota.enabled=false",
    "pdfconduit.web.ocr.enabled=false",
    "pdfconduit.web.pdf.max-pages=3"
})
@AutoConfigureMockMvc
class PipelineLimitsTest {

    @Autowired
    private MockMvc mvc;

    private static MockMultipartFile pdf(byte[] bytes) {
        return new MockMultipartFile("files", "a.pdf", "application/pdf", bytes);
    }

    private static String source(String id) {
        return "{\"id\":\"" + id + "\",\"kind\":\"SOURCE\",\"x\":0,\"y\":0,\"files\":[\"a.pdf\"]}";
    }

    private static String model(String nodes, String connections) {
        return "{\"nodes\":[" + nodes + "],\"connections\":[" + connections + "]}";
    }

    private static String edge(String from, String to) {
        return "{\"fromNodeId\":\"" + from + "\",\"toNodeId\":\"" + to + "\"}";
    }

    // ------------------------------------------------------------------ render DPI

    /** A TO_IMAGES node at 1200 DPI is the OOM vector: it must be refused, exactly like /api/to-images. */
    @Test
    void toImagesNodeHugeDpi_rejected() throws Exception {
        String json = model(
            source("s") + ",{\"id\":\"i\",\"kind\":\"TO_IMAGES\",\"x\":0,\"y\":0,"
                + "\"imageFormat\":\"PNG\",\"imageDpi\":1200,\"jpegQuality\":0.85}",
            edge("s", "i"));

        mvc.perform(multipart("/api/pipeline/run").file(pdf(TestPdfs.blank(1)))
                .param("pipeline", json))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("bad_request"))
            .andExpect(jsonPath("$.error", containsString("exceeds the maximum allowed")));
    }

    /** The same node at a sane DPI still renders — the guard rejects abuse, not the feature. */
    @Test
    void toImagesNodeNormalDpi_runs() throws Exception {
        String json = model(
            source("s") + ",{\"id\":\"i\",\"kind\":\"TO_IMAGES\",\"x\":0,\"y\":0,"
                + "\"imageFormat\":\"PNG\",\"imageDpi\":72,\"jpegQuality\":0.85}",
            edge("s", "i"));

        mvc.perform(multipart("/api/pipeline/run").file(pdf(TestPdfs.blank(1)))
                .param("pipeline", json))
            .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ page count

    /** A source document over {@code pdf.max-pages} (3 here) must be refused, as on /api/extract. */
    @Test
    void sourceOverPageCap_returns422() throws Exception {
        String json = model(
            source("s") + ",{\"id\":\"r\",\"kind\":\"ROTATE\",\"x\":0,\"y\":0,"
                + "\"pages\":\"\",\"angle\":90}",
            edge("s", "r"));

        mvc.perform(multipart("/api/pipeline/run").file(pdf(TestPdfs.blank(5)))
                .param("pipeline", json))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("operation_failed"))
            .andExpect(jsonPath("$.error", containsString("maximum page count")));
    }

    /** A merge of several under-cap inputs whose combined page count is over it is refused too. */
    @Test
    void mergeOverPageCap_returns422() throws Exception {
        String json = model(
            "{\"id\":\"s\",\"kind\":\"SOURCE\",\"x\":0,\"y\":0,\"files\":[\"a.pdf\",\"b.pdf\"]},"
                + "{\"id\":\"m\",\"kind\":\"MERGE\",\"x\":0,\"y\":0}",
            edge("s", "m"));

        mvc.perform(multipart("/api/pipeline/run")
                .file(pdf(TestPdfs.blank(2)))
                .file(new MockMultipartFile("files", "b.pdf", "application/pdf", TestPdfs.blank(2)))
                .param("pipeline", json))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("operation_failed"))
            .andExpect(jsonPath("$.error", containsString("maximum page count")));
    }

    /** A pipeline within every ceiling still runs green. */
    @Test
    void smallPipeline_runsGreen() throws Exception {
        String json = model(
            source("s") + ",{\"id\":\"r\",\"kind\":\"ROTATE\",\"x\":0,\"y\":0,"
                + "\"pages\":\"\",\"angle\":90}",
            edge("s", "r"));

        mvc.perform(multipart("/api/pipeline/run").file(pdf(TestPdfs.blank(2)))
                .param("pipeline", json))
            .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------------ OCR

    /** With OCR disabled an OCR node must be refused with the same code /api/ocr returns (415). */
    @Test
    void ocrNodeWhileOcrDisabled_returns415() throws Exception {
        String json = model(
            source("s") + ",{\"id\":\"o\",\"kind\":\"OCR\",\"x\":0,\"y\":0,"
                + "\"ocrLanguages\":\"eng\",\"ocrDpi\":300}",
            edge("s", "o"));

        mvc.perform(multipart("/api/pipeline/run").file(pdf(TestPdfs.blank(1)))
                .param("pipeline", json))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(jsonPath("$.code").value("ocr_disabled"));
    }

    // ------------------------------------------------------- crafted name references

    /** {@code files:["/"]} has no name component — 400, not an NPE surfacing as 500. */
    @Test
    void rootSourceName_returns400() throws Exception {
        String json = model(
            "{\"id\":\"s\",\"kind\":\"SOURCE\",\"x\":0,\"y\":0,\"files\":[\"/\"]}",
            "");

        mvc.perform(multipart("/api/pipeline/run").file(pdf(TestPdfs.blank(1)))
                .param("pipeline", json))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("bad_request"));
    }

    /** The empty-string variant is the same client mistake, and must not 500 either. */
    @Test
    void emptySourceName_returns400() throws Exception {
        String json = model(
            "{\"id\":\"s\",\"kind\":\"SOURCE\",\"x\":0,\"y\":0,\"files\":[\"\"]}",
            "");

        mvc.perform(multipart("/api/pipeline/run").file(pdf(TestPdfs.blank(1)))
                .param("pipeline", json))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("bad_request"));
    }

    /** A watermark node whose image reference has no name component is a 400 as well. */
    @Test
    void rootWatermarkImageName_returns400() throws Exception {
        String json = model(
            source("s") + ",{\"id\":\"w\",\"kind\":\"WATERMARK\",\"x\":0,\"y\":0,"
                + "\"wmText\":\"\",\"wmImage\":\"/\",\"wmOpacity\":0.3,"
                + "\"wmRotation\":45,\"wmScale\":0.7}",
            edge("s", "w"));

        mvc.perform(multipart("/api/pipeline/run").file(pdf(TestPdfs.blank(1)))
                .param("pipeline", json))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("bad_request"));
    }
}
