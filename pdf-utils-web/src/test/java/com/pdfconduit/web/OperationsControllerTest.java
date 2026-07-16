package com.pdfconduit.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end REST tests: real Spring context, real core operations, PDFs generated
 * in-memory with PDFBox. No LibreOffice needed (PDF/image inputs only).
 */
@SpringBootTest
class OperationsControllerTest {

    @Autowired
    private WebApplicationContext context;

    private final ObjectMapper json = new ObjectMapper();

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    private static MockMultipartFile pdf(String field, String name, byte[] bytes) {
        return new MockMultipartFile(field, name, "application/pdf", bytes);
    }

    // --------------------------------------------------------------- info

    @Test
    void health_reportsUp() throws Exception {
        mvc().perform(get("/api/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void operations_listsCatalog() throws Exception {
        mvc().perform(get("/api/operations"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id=='merge')].cardinality").value("REDUCE"))
            .andExpect(jsonPath("$[?(@.id=='extract')].multiOutput").value(true));
    }

    // --------------------------------------------------------------- merge

    @Test
    void merge_twoPdfs_returnsCombinedPdf() throws Exception {
        byte[] a = TestPdfs.blank(1);
        byte[] b = TestPdfs.blank(2);
        MvcResult result = mvc().perform(multipart("/api/merge")
                .file(pdf("files", "a.pdf", a))
                .file(pdf("files", "b.pdf", b)))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
            .andReturn();
        assertThat(TestPdfs.pageCount(result.getResponse().getContentAsByteArray())).isEqualTo(3);
    }

    // ------------------------------------------------------------- compress

    @Test
    void compress_returnsPdfWithMetricHeaders() throws Exception {
        byte[] a = TestPdfs.withText("compress me");
        mvc().perform(multipart("/api/compress")
                .file(pdf("files", "a.pdf", a))
                .param("targetSize", "10MB"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(header().exists("X-Target-Reached"))
            .andExpect(header().exists("X-Original-Bytes"))
            .andExpect(header().exists("X-Result-Bytes"));
    }

    // --------------------------------------------------------------- rotate

    @Test
    void rotate_singleFile_returnsPdf() throws Exception {
        byte[] a = TestPdfs.blank(2);
        mvc().perform(multipart("/api/rotate")
                .file(pdf("files", "a.pdf", a))
                .param("angle", "90"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF));
    }

    // -------------------------------------------------------------- extract

    @Test
    void extract_combine_returnsSelectedPages() throws Exception {
        byte[] a = TestPdfs.blank(5);
        MvcResult result = mvc().perform(multipart("/api/extract")
                .file(pdf("file", "a.pdf", a))
                .param("pages", "1,3"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andReturn();
        assertThat(TestPdfs.pageCount(result.getResponse().getContentAsByteArray())).isEqualTo(2);
    }

    @Test
    void extract_separate_returnsZip() throws Exception {
        byte[] a = TestPdfs.blank(3);
        mvc().perform(multipart("/api/extract")
                .file(pdf("file", "a.pdf", a))
                .param("separate", "true"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.parseMediaType("application/zip")))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.containsString("extract_results.zip")));
    }

    // ------------------------------------------------------------- metadata

    @Test
    void metadata_readThenEdit_roundtrips() throws Exception {
        byte[] a = TestPdfs.blank(1);

        mvc().perform(multipart("/api/metadata/read").file(pdf("file", "a.pdf", a)))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON));

        MvcResult edited = mvc().perform(multipart("/api/metadata")
                .file(pdf("file", "a.pdf", a))
                .param("title", "Hello Title")
                .param("author", "Ada"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andReturn();

        // Re-read the edited PDF's metadata via the endpoint.
        byte[] editedPdf = edited.getResponse().getContentAsByteArray();
        MvcResult reread = mvc().perform(multipart("/api/metadata/read")
                .file(pdf("file", "edited.pdf", editedPdf)))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode node = json.readTree(reread.getResponse().getContentAsString());
        assertThat(node.get("title").asText()).isEqualTo("Hello Title");
        assertThat(node.get("author").asText()).isEqualTo("Ada");
    }

    // ------------------------------------------------------- protect/unlock

    @Test
    void protectThenUnlock_roundtrips() throws Exception {
        byte[] a = TestPdfs.blank(1);
        MvcResult protectedResult = mvc().perform(multipart("/api/protect")
                .file(pdf("files", "a.pdf", a))
                .param("userPassword", "s3cr3t"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andReturn();

        byte[] locked = protectedResult.getResponse().getContentAsByteArray();
        mvc().perform(multipart("/api/unlock")
                .file(pdf("files", "locked.pdf", locked))
                .param("password", "s3cr3t"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF));
    }

    // ------------------------------------------------------------ watermark

    @Test
    void watermark_text_returnsPdf() throws Exception {
        byte[] a = TestPdfs.blank(1);
        mvc().perform(multipart("/api/watermark")
                .file(pdf("files", "a.pdf", a))
                .param("text", "DRAFT"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF));
    }

    // ---------------------------------------------------------------- errors

    @Test
    void extract_invalidPageRange_returns400() throws Exception {
        byte[] a = TestPdfs.blank(2);
        mvc().perform(multipart("/api/extract")
                .file(pdf("file", "a.pdf", a))
                .param("pages", "5-9"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("invalid_page_range"));
    }

    @Test
    void extract_corruptPdf_returns422() throws Exception {
        byte[] notAPdf = "this is definitely not a pdf".getBytes();
        mvc().perform(multipart("/api/extract")
                .file(pdf("file", "broken.pdf", notAPdf)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("operation_failed"));
    }

    @Test
    void watermark_bothTextAndImage_returns400() throws Exception {
        byte[] a = TestPdfs.blank(1);
        MockMultipartFile image = new MockMultipartFile("image", "wm.png", "image/png", new byte[]{1, 2, 3});
        mvc().perform(multipart("/api/watermark")
                .file(pdf("files", "a.pdf", a))
                .file(image)
                .param("text", "DRAFT"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("bad_request"));
    }
}
