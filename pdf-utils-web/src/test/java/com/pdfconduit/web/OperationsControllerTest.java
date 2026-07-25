package com.pdfconduit.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonPrimitive;
import com.pdfconduit.core.pipeline.Connection;
import com.pdfconduit.core.pipeline.NodeKind;
import com.pdfconduit.core.pipeline.PipelineModel;
import com.pdfconduit.core.pipeline.PipelineNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end REST tests for the stateless, in-memory backend: real Spring context, real core
 * operations, PDFs generated in-memory with PDFBox. No LibreOffice needed (PDF/image inputs only).
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

    private static int zipEntryCount(byte[] zip) throws java.io.IOException {
        int n = 0;
        try (java.util.zip.ZipInputStream in =
                 new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            while (in.getNextEntry() != null) n++;
        }
        return n;
    }

    /** Entry name → entry bytes, in archive order. */
    private static java.util.LinkedHashMap<String, byte[]> zipEntries(byte[] zip)
            throws java.io.IOException {
        java.util.LinkedHashMap<String, byte[]> out = new java.util.LinkedHashMap<>();
        try (java.util.zip.ZipInputStream in =
                 new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            java.util.zip.ZipEntry e;
            while ((e = in.getNextEntry()) != null) out.put(e.getName(), in.readAllBytes());
        }
        return out;
    }

    /** Page count of every entry in a ZIP of PDFs, in archive order. */
    private static java.util.List<Integer> zipPageCounts(byte[] zip) throws java.io.IOException {
        java.util.List<Integer> counts = new java.util.ArrayList<>();
        for (byte[] entry : zipEntries(zip).values()) counts.add(TestPdfs.pageCount(entry));
        return counts;
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
            .andExpect(jsonPath("$[?(@.id=='extract')].multiOutput").value(true))
            // Availability flags: everything is available except OCR, which is disabled by
            // default (pdfconduit.web.ocr.enabled=false) and must be flagged so the UI hides it.
            .andExpect(jsonPath("$[?(@.id=='merge')].available").value(true))
            .andExpect(jsonPath("$[?(@.id=='to-pdf')].available").value(true))
            .andExpect(jsonPath("$[?(@.id=='ocr')].available").value(false));
    }

    @Test
    void capabilities_reportFlags_withoutSpawningTesseractWhenOcrDisabled() throws Exception {
        // OCR is disabled in the test context, so ocrLanguages must be [] WITHOUT any
        // tesseract discovery (the binary may not exist on the build machine).
        mvc().perform(get("/api/capabilities"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.officeEnabled").value(true))
            .andExpect(jsonPath("$.ocrEnabled").value(false))
            .andExpect(jsonPath("$.ocrLanguages").isArray())
            .andExpect(jsonPath("$.ocrLanguages").isEmpty());
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
                .file(pdf("files", "a.pdf", a))
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
                .file(pdf("files", "a.pdf", a))
                .param("separate", "true"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.parseMediaType("application/zip")))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.containsString("extract_results.zip")));
    }

    @Test
    void extract_combine_multipleFiles_returnsZip() throws Exception {
        byte[] a = TestPdfs.blank(5);
        byte[] b = TestPdfs.blank(4);
        MvcResult result = mvc().perform(multipart("/api/extract")
                .file(pdf("files", "a.pdf", a))
                .file(pdf("files", "b.pdf", b))
                .param("pages", "1,3"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.parseMediaType("application/zip")))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.containsString("extract_results.zip")))
            .andReturn();
        // Two source files → one combined PDF per file in the archive.
        assertThat(zipEntryCount(result.getResponse().getContentAsByteArray())).isEqualTo(2);
    }

    @Test
    void extract_splitEvery_returnsZipOfNPageParts() throws Exception {
        byte[] a = TestPdfs.blank(7);
        MvcResult result = mvc().perform(multipart("/api/extract")
                .file(pdf("files", "a.pdf", a))
                .param("splitEvery", "3"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.parseMediaType("application/zip")))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.containsString("extract_results.zip")))
            .andReturn();

        // 7 pages every 3 → 3 parts of 3 + 3 + 1, numbered from the source file's name.
        java.util.LinkedHashMap<String, byte[]> entries =
            zipEntries(result.getResponse().getContentAsByteArray());
        assertThat(entries.keySet())
            .containsExactly("a_extracted_1.pdf", "a_extracted_2.pdf", "a_extracted_3.pdf");
        assertThat(zipPageCounts(result.getResponse().getContentAsByteArray()))
            .containsExactly(3, 3, 1);
    }

    @Test
    void extract_splitEvery_chunksWithinTheSelectedRange() throws Exception {
        byte[] a = TestPdfs.blank(10);
        MvcResult result = mvc().perform(multipart("/api/extract")
                .file(pdf("files", "a.pdf", a))
                .param("pages", "2-6")
                .param("splitEvery", "2"))
            .andExpect(status().isOk())
            .andReturn();

        // The range narrows what is split (5 pages), splitEvery only cuts it up: 2 + 2 + 1.
        assertThat(zipPageCounts(result.getResponse().getContentAsByteArray()))
            .containsExactly(2, 2, 1);
    }

    @Test
    void extract_splitEvery_beyondPageCount_returnsOnePart() throws Exception {
        byte[] a = TestPdfs.blank(3);
        MvcResult result = mvc().perform(multipart("/api/extract")
                .file(pdf("files", "a.pdf", a))
                .param("splitEvery", "99"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.parseMediaType("application/zip")))
            .andReturn();

        assertThat(zipPageCounts(result.getResponse().getContentAsByteArray())).containsExactly(3);
    }

    @Test
    void extract_splitEvery_belowOne_isRejected() throws Exception {
        mvc().perform(multipart("/api/extract")
                .file(pdf("files", "a.pdf", TestPdfs.blank(3)))
                .param("splitEvery", "0"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("bad_request"));
    }

    // ------------------------------------------------------------- metadata

    @Test
    void metadata_readThenEdit_roundtrips() throws Exception {
        byte[] a = TestPdfs.blank(1);

        mvc().perform(multipart("/api/metadata/read").file(pdf("file", "a.pdf", a)))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON));

        MvcResult edited = mvc().perform(multipart("/api/metadata")
                .file(pdf("files", "a.pdf", a))
                .param("title", "Hello Title")
                .param("author", "Ada"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andReturn();

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

    // --------------------------------------------------------------- redact

    @Test
    void redact_region_returnsPdf() throws Exception {
        byte[] a = TestPdfs.withText("secret data here");
        String regions = "[{\"pageIndex\":0,\"x\":20,\"y\":20,\"width\":100,\"height\":30}]";
        MvcResult result = mvc().perform(multipart("/api/redact")
                .file(pdf("file", "a.pdf", a))
                .param("regions", regions))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andReturn();
        assertThat(TestPdfs.pageCount(result.getResponse().getContentAsByteArray())).isEqualTo(1);
    }

    /**
     * The redaction contract, proved end-to-end: the data is gone from the returned bytes AND the
     * response says what was actually blacked out, so a client never has to trust the filename.
     */
    @Test
    void redact_reportsWhatWasActuallyBlackedOut() throws Exception {
        byte[] a = TestPdfs.withText("secret john.doe@example.com data");
        String regions = "[{\"pageIndex\":0,\"x\":60,\"y\":120,\"width\":420,\"height\":40}]";
        MvcResult result = mvc().perform(multipart("/api/redact")
                .file(pdf("file", "a.pdf", a))
                .param("regions", regions))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(header().string("X-Redacted-Pages", "1"))
            .andExpect(header().string("X-Redacted-Regions", "1"))
            .andReturn();
        assertThat(TestPdfs.text(result.getResponse().getContentAsByteArray()))
            .doesNotContain("john.doe@example.com");
    }

    /**
     * A region past the last page used to be dropped silently: 200 OK, {@code a_redacted.pdf},
     * personal data fully intact. It must now fail — never a 2xx.
     */
    @Test
    void redact_pageIndexPastLastPage_isRejected() throws Exception {
        byte[] a = TestPdfs.withText("secret john.doe@example.com data");
        String regions = "[{\"pageIndex\":5,\"x\":20,\"y\":20,\"width\":100,\"height\":30}]";
        mvc().perform(multipart("/api/redact")
                .file(pdf("file", "a.pdf", a))
                .param("regions", regions))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("operation_failed"))
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("page 6")));
    }

    /** A zero-area box covers nothing; accepting it would be a silent non-redaction (400). */
    @Test
    void redact_zeroAreaRegion_isRejected() throws Exception {
        byte[] a = TestPdfs.withText("secret data here");
        String regions = "[{\"pageIndex\":0,\"x\":20,\"y\":20,\"width\":0,\"height\":30}]";
        mvc().perform(multipart("/api/redact")
                .file(pdf("file", "a.pdf", a))
                .param("regions", regions))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("bad_request"))
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("no area")));
    }

    /** An empty region list would return the untouched upload named "_redacted" — refuse it. */
    @Test
    void redact_emptyRegionList_isRejected() throws Exception {
        byte[] a = TestPdfs.withText("secret data here");
        mvc().perform(multipart("/api/redact")
                .file(pdf("file", "a.pdf", a))
                .param("regions", "[]"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("bad_request"));
    }

    // ---------------------------------------------------------- auto-redact

    /** The happy path of the scan → auto-redact hand-off: values blacked out, counts reported. */
    @Test
    void autoRedact_detectedValues_areBlackedOutAndCounted() throws Exception {
        byte[] a = TestPdfs.withText("Contact john.doe@example.com for details");
        MvcResult result = mvc().perform(multipart("/api/auto-redact")
                .file(pdf("file", "a.pdf", a)))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(header().string("X-Redacted-Pages", "1"))
            .andExpect(header().string("X-Redacted-Regions", "1"))
            .andReturn();
        assertThat(TestPdfs.text(result.getResponse().getContentAsByteArray()))
            .doesNotContain("john.doe@example.com");
    }

    /**
     * Art. 9 keyword flags (health, religion, …) carry no coordinates, so there is nothing to
     * black out. This used to stream the ORIGINAL file back as {@code a_redacted.pdf}, 200 OK —
     * the most dangerous possible lie. It must be an honest 422 with no file at all.
     */
    @Test
    void autoRedact_onlyKeywordFindings_refusesInsteadOfFakingARedactedFile() throws Exception {
        byte[] a = TestPdfs.withText("The patient diagnosis and medication are on file.");
        mvc().perform(multipart("/api/auto-redact")
                .file(pdf("file", "a.pdf", a))
                .param("categories", "SPECIAL_CATEGORY"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(header().doesNotExist("Content-Disposition"))
            .andExpect(jsonPath("$.code").value("operation_failed"))
            .andExpect(jsonPath("$.error")
                .value(org.hamcrest.Matchers.containsString("Nothing could be redacted")));
    }

    /** Same refusal when the scan finds nothing at all — no file may claim to be redacted. */
    @Test
    void autoRedact_cleanDocument_refusesInsteadOfFakingARedactedFile() throws Exception {
        byte[] a = TestPdfs.withText("Nothing sensitive here, just a friendly note.");
        mvc().perform(multipart("/api/auto-redact")
                .file(pdf("file", "a.pdf", a)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("operation_failed"));
    }

    @Test
    void redact_reOcr_whenOcrUnavailable_isNoOpAndStillRedacts() throws Exception {
        // OCR is disabled by default (and tesseract is absent in CI), so reOcr=true must be a clean
        // no-op layered on top of redaction: the redacted PDF is still returned, never a crash/415.
        byte[] a = TestPdfs.withText("secret data here");
        String regions = "[{\"pageIndex\":0,\"x\":20,\"y\":20,\"width\":100,\"height\":30}]";
        MvcResult result = mvc().perform(multipart("/api/redact")
                .file(pdf("file", "a.pdf", a))
                .param("regions", regions)
                .param("reOcr", "true"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andReturn();
        assertThat(TestPdfs.pageCount(result.getResponse().getContentAsByteArray())).isEqualTo(1);
    }

    // ------------------------------------------------------------- to-images

    @Test
    void toImages_multiPage_returnsZip() throws Exception {
        byte[] a = TestPdfs.blank(2);
        mvc().perform(multipart("/api/to-images")
                .file(pdf("files", "a.pdf", a))
                .param("format", "png")
                .param("dpi", "72"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.parseMediaType("application/zip")))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.containsString("to-images_results.zip")));
    }

    @Test
    void toImages_singlePage_streamsImageDirectly() throws Exception {
        byte[] a = TestPdfs.blank(1);
        MvcResult result = mvc().perform(multipart("/api/to-images")
                .file(pdf("files", "a.pdf", a))
                .param("format", "png")
                .param("dpi", "72"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.IMAGE_PNG))
            .andReturn();
        assertThat(result.getResponse().getContentAsByteArray()).isNotEmpty();
    }

    /**
     * A non-PDF upload still renders. The request-wide render pre-flight routes every input to PDF
     * before anything is rasterised and hands the routed bytes to the per-file pass; routing keys
     * off the file NAME, so a converted upload that kept its {@code .png} name must be recognised
     * as the PDF it now is instead of being fed back through the image converter.
     */
    @Test
    void toImages_imageUpload_isConvertedThenRendered() throws Exception {
        MvcResult result = mvc().perform(multipart("/api/to-images")
                .file(new MockMultipartFile("files", "logo.png", "image/png", pngLogo()))
                .param("format", "png")
                .param("dpi", "72"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.IMAGE_PNG))
            .andReturn();
        assertThat(result.getResponse().getContentAsByteArray()).isNotEmpty();
    }

    @Test
    void toImages_multipleFiles_returnsZip() throws Exception {
        byte[] a = TestPdfs.blank(1);
        byte[] b = TestPdfs.blank(1);
        mvc().perform(multipart("/api/to-images")
                .file(pdf("files", "a.pdf", a))
                .file(pdf("files", "b.pdf", b))
                .param("format", "png")
                .param("dpi", "72"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.parseMediaType("application/zip")))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.containsString("to-images_results.zip")));
    }

    @Test
    void toImages_lowerQuality_yieldsSmallerJpeg() throws Exception {
        byte[] a = TestPdfs.withText("quality matters for jpeg output");
        byte[] high = mvc().perform(multipart("/api/to-images")
                .file(pdf("files", "a.pdf", a))
                .param("format", "jpeg")
                .param("dpi", "150")
                .param("quality", "0.95"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.IMAGE_JPEG))
            .andReturn().getResponse().getContentAsByteArray();
        byte[] low = mvc().perform(multipart("/api/to-images")
                .file(pdf("files", "a.pdf", a))
                .param("format", "jpeg")
                .param("dpi", "150")
                .param("quality", "0.1"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.IMAGE_JPEG))
            .andReturn().getResponse().getContentAsByteArray();
        assertThat(low.length).isLessThan(high.length);
    }

    // -------------------------------------------------------------- to-text

    @Test
    void toText_returnsPlainText() throws Exception {
        byte[] a = TestPdfs.withText("hello extracted world");
        MvcResult result = mvc().perform(multipart("/api/to-text")
                .file(pdf("files", "a.pdf", a)))
            .andExpect(status().isOk())
            .andExpect(content().contentType(new MediaType(MediaType.TEXT_PLAIN, java.nio.charset.StandardCharsets.UTF_8)))
            .andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("hello extracted world");
    }

    @Test
    void toText_multipleFiles_returnsZip() throws Exception {
        byte[] a = TestPdfs.withText("first document text");
        byte[] b = TestPdfs.withText("second document text");
        mvc().perform(multipart("/api/to-text")
                .file(pdf("files", "a.pdf", a))
                .file(pdf("files", "b.pdf", b)))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.parseMediaType("application/zip")))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.containsString("to-text_results.zip")));
    }

    @Test
    void metadata_multipleFiles_returnsZip() throws Exception {
        byte[] a = TestPdfs.blank(1);
        byte[] b = TestPdfs.blank(2);
        mvc().perform(multipart("/api/metadata")
                .file(pdf("files", "a.pdf", a))
                .file(pdf("files", "b.pdf", b))
                .param("title", "Batch Title"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.parseMediaType("application/zip")))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.containsString("metadata_results.zip")));
    }

    // --------------------------------------------------------------- render

    @Test
    void render_singlePage_returnsPng() throws Exception {
        byte[] a = TestPdfs.blank(2);
        MvcResult result = mvc().perform(multipart("/api/render")
                .file(pdf("file", "a.pdf", a))
                .param("page", "0")
                .param("dpi", "72"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.IMAGE_PNG))
            .andReturn();
        assertThat(result.getResponse().getContentAsByteArray()).isNotEmpty();
    }

    // ------------------------------------------------------------- pipeline

    @Test
    void pipelineKinds_listsCatalog() throws Exception {
        mvc().perform(get("/api/pipeline/kinds"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.name=='SOURCE')].isSource").value(true))
            .andExpect(jsonPath("$[?(@.name=='MERGE')].isReduce").value(true))
            .andExpect(jsonPath("$[?(@.name=='TO_IMAGES')].isExport").value(true));
    }

    @Test
    void pipelineRun_sourceRotateMerge_returnsZip() throws Exception {
        PipelineModel model = new PipelineModel();
        PipelineNode src = new PipelineNode("src", NodeKind.SOURCE, 0, 0);
        src.files.add(Path.of("a.pdf"));
        src.files.add(Path.of("b.pdf"));
        PipelineNode rot = new PipelineNode("rot", NodeKind.ROTATE, 0, 0);
        rot.angle = 90;
        PipelineNode mrg = new PipelineNode("mrg", NodeKind.MERGE, 0, 0);
        model.nodes.add(src);
        model.nodes.add(rot);
        model.nodes.add(mrg);
        model.connections.add(new Connection("src", "rot"));
        model.connections.add(new Connection("rot", "mrg"));

        mvc().perform(multipart("/api/pipeline/run")
                .file(pdf("files", "a.pdf", TestPdfs.blank(1)))
                .file(pdf("files", "b.pdf", TestPdfs.blank(2)))
                .param("pipeline", toJson(model)))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.parseMediaType("application/zip")))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.containsString("pipeline_results.zip")));
    }

    @Test
    void pipelineRun_imageWatermark_returnsZip() throws Exception {
        PipelineModel model = new PipelineModel();
        PipelineNode src = new PipelineNode("s", NodeKind.SOURCE, 0, 0);
        src.files.add(Path.of("doc.pdf"));
        PipelineNode wm = new PipelineNode("w", NodeKind.WATERMARK, 0, 0);
        wm.wmText = "";
        wm.wmImage = "logo.png";   // name reference; bytes ride along as a nodeAssets part
        model.nodes.add(src);
        model.nodes.add(wm);
        model.connections.add(new Connection("s", "w"));

        MockMultipartFile image = new MockMultipartFile("nodeAssets", "logo.png", "image/png", pngLogo());
        mvc().perform(multipart("/api/pipeline/run")
                .file(pdf("files", "doc.pdf", TestPdfs.blank(2)))
                .file(image)
                .param("pipeline", toJson(model)))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.parseMediaType("application/zip")))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.containsString("pipeline_results.zip")));
    }

    @Test
    void pipelineRun_imageWatermarkMissingAsset_returns400() throws Exception {
        PipelineModel model = new PipelineModel();
        PipelineNode src = new PipelineNode("s", NodeKind.SOURCE, 0, 0);
        src.files.add(Path.of("doc.pdf"));
        PipelineNode wm = new PipelineNode("w", NodeKind.WATERMARK, 0, 0);
        wm.wmText = "";
        wm.wmImage = "logo.png";
        model.nodes.add(src);
        model.nodes.add(wm);
        model.connections.add(new Connection("s", "w"));

        mvc().perform(multipart("/api/pipeline/run")
                .file(pdf("files", "doc.pdf", TestPdfs.blank(1)))
                .param("pipeline", toJson(model)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void pipelineValidate_missingWatermarkText_returnsErrors() throws Exception {
        PipelineModel model = new PipelineModel();
        PipelineNode src = new PipelineNode("s", NodeKind.SOURCE, 0, 0);
        src.files.add(Path.of("a.pdf"));
        PipelineNode wm = new PipelineNode("w", NodeKind.WATERMARK, 0, 0);
        wm.wmText = "";
        wm.wmImage = "";
        model.nodes.add(src);
        model.nodes.add(wm);
        model.connections.add(new Connection("s", "w"));

        mvc().perform(multipart("/api/pipeline/validate").param("pipeline", toJson(model)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].nodeId").value("w"))
            .andExpect(jsonPath("$[0].message", org.hamcrest.Matchers.containsString("text or an image")));
    }

    // ---------------------------------------------------------------- errors

    @Test
    void extract_invalidPageRange_returns400() throws Exception {
        byte[] a = TestPdfs.blank(2);
        mvc().perform(multipart("/api/extract")
                .file(pdf("files", "a.pdf", a))
                .param("pages", "5-9"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("invalid_page_range"));
    }

    @Test
    void extract_corruptPdf_returns422() throws Exception {
        byte[] notAPdf = "this is definitely not a pdf".getBytes();
        mvc().perform(multipart("/api/extract")
                .file(pdf("files", "broken.pdf", notAPdf)))
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

    // --------------------------------------------------------------- helpers

    /** A small PNG for image-watermark assets. */
    private static byte[] pngLogo() throws java.io.IOException {
        java.awt.image.BufferedImage img =
            new java.awt.image.BufferedImage(48, 48, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(java.awt.Color.BLUE);
        g.fillOval(2, 2, 44, 44);
        g.dispose();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** Serialises a model the same way core's PipelineStore does (Path fields → strings). */
    private static String toJson(PipelineModel model) {
        Gson gson = new GsonBuilder()
            .registerTypeHierarchyAdapter(Path.class,
                (com.google.gson.JsonSerializer<Path>) (src, t, ctx) -> new JsonPrimitive(src.toString()))
            .create();
        return gson.toJson(model);
    }
}
