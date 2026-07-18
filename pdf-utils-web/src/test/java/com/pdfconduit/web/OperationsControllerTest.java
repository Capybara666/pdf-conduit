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

    // ------------------------------------------------------------- to-images

    @Test
    void toImages_multiPage_returnsZip() throws Exception {
        byte[] a = TestPdfs.blank(2);
        mvc().perform(multipart("/api/to-images")
                .file(pdf("file", "a.pdf", a))
                .param("format", "png")
                .param("dpi", "72"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.parseMediaType("application/zip")))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.containsString("to-images_results.zip")));
    }

    // -------------------------------------------------------------- to-text

    @Test
    void toText_returnsPlainText() throws Exception {
        byte[] a = TestPdfs.withText("hello extracted world");
        MvcResult result = mvc().perform(multipart("/api/to-text")
                .file(pdf("file", "a.pdf", a)))
            .andExpect(status().isOk())
            .andExpect(content().contentType(new MediaType(MediaType.TEXT_PLAIN, java.nio.charset.StandardCharsets.UTF_8)))
            .andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("hello extracted world");
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
