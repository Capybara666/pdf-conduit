package com.pdfconduit.web.cost;

import com.pdfconduit.core.pipeline.NodeKind;
import com.pdfconduit.web.cost.CostSpec.Trait;
import com.pdfconduit.web.support.Endpoints;

import java.util.EnumMap;
import java.util.Map;

/**
 * The single place where every unit of work declares how it scales: one {@link CostSpec} per
 * {@code /api} endpoint and one per pipeline {@link NodeKind}. {@link Endpoints} says <em>which
 * tier</em> a request belongs to; this says <em>what it will cost</em>, and {@link CostModel} turns
 * that into bytes.
 *
 * <p>Both catalogs are exhaustive by test: {@code CostCatalogCompletenessTest} enumerates the live
 * Spring handler mappings and the {@code NodeKind} enum and fails when either grows an entry that
 * declares no cost. Without that, a new rasterising endpoint or a new page-multiplying node would
 * be admitted on an estimate of zero — the exact way this class of hole gets reintroduced.
 *
 * <p><b>Reading the numbers.</b> {@code outputFactor} is result ÷ input for operations whose output
 * tracks their input; {@code workingFactor} is the transient heap on top of what is retained (the
 * parsed PDFBox document is roughly the file size again, a compressor re-encoding images needs
 * more). They are deliberately coarse: the estimate exists to refuse the impossible in milliseconds,
 * and the running tallies in {@link com.pdfconduit.web.guard.OutputBudget} remain the exact backstop
 * for everything that is admitted.
 */
public final class CostCatalog {

    private CostCatalog() {}

    /** A page operation that rewrites the document: one result, roughly the size of the input. */
    private static final CostSpec REWRITE = CostSpec.of(1.05, 1.5);

    private static final Map<String, CostSpec> BY_PATH = Map.ofEntries(
        // Catalog endpoints: no user data is opened, so there is nothing to estimate.
        Map.entry("/api/health", CostSpec.NEGLIGIBLE),
        Map.entry("/api/operations", CostSpec.NEGLIGIBLE),
        Map.entry("/api/capabilities", CostSpec.NEGLIGIBLE),
        Map.entry("/api/pipeline/kinds", CostSpec.NEGLIGIBLE),
        Map.entry("/api/pipeline/validate", CostSpec.NEGLIGIBLE),

        // Read-only analyses: they parse an arbitrary upload but return almost nothing.
        Map.entry("/api/metadata/read", CostSpec.of(0.01, 1.5)),
        Map.entry("/api/form-fields", CostSpec.of(0.01, 1.5)),

        // Document rewrites.
        Map.entry("/api/rotate", REWRITE),
        Map.entry("/api/protect", REWRITE),
        Map.entry("/api/unlock", REWRITE),
        Map.entry("/api/metadata", REWRITE),
        Map.entry("/api/watermark", REWRITE),
        Map.entry("/api/crop", REWRITE),
        Map.entry("/api/nup", REWRITE),
        Map.entry("/api/page-marks", REWRITE),
        Map.entry("/api/sign", REWRITE),
        Map.entry("/api/repair", REWRITE),
        Map.entry("/api/extract", CostSpec.of(1.0, 1.5)),

        // Merge and arrange can hand back more pages than any single input carries.
        Map.entry("/api/merge", CostSpec.of(1.05, 1.5, Trait.AMPLIFIES_PAGES)),
        Map.entry("/api/arrange", CostSpec.of(1.05, 1.5, Trait.AMPLIFIES_PAGES)),

        // The compressor walks a re-encode ladder, holding candidate renditions while it decides.
        Map.entry("/api/compress", CostSpec.of(1.0, 2.5)),

        // Images and office documents become PDFs, which can be a good deal larger; office
        // conversion shells out to LibreOffice.
        Map.entry("/api/to-pdf", CostSpec.of(1.5, 2.0, Trait.EXTERNAL_PROCESS)),

        // Rasterisers: output is pages × DPI², not a multiple of the upload.
        Map.entry("/api/to-images",
            CostSpec.of(0, 1.5, Trait.RASTERISES, Trait.UNBOUNDED_OUTPUT)),
        Map.entry("/api/render", CostSpec.of(0.1, 1.5, Trait.RASTERISES)),
        Map.entry("/api/redact", CostSpec.of(2.0, 2.0, Trait.RASTERISES)),
        Map.entry("/api/auto-redact", CostSpec.of(2.0, 2.0, Trait.RASTERISES)),
        Map.entry("/api/ocr",
            CostSpec.of(1.5, 2.5, Trait.RASTERISES, Trait.EXTERNAL_PROCESS)),

        // Text extraction shrinks; the GDPR scans return a report, not a document.
        Map.entry("/api/to-text", CostSpec.of(0.3, 1.5)),
        Map.entry("/api/gdpr-scan", CostSpec.of(0.05, 2.0)),
        Map.entry("/api/gdpr-scan-batch", CostSpec.of(0.05, 2.0)),

        // The pipeline is the one endpoint whose cost is not a function of its uploads alone: the
        // executor keeps every node's outputs alive at once, so the graph decides the multiplier.
        // CostModel.forPipeline walks it; this entry is the fallback when there is no graph yet.
        Map.entry("/api/pipeline/run",
            CostSpec.of(1.05, 1.5, Trait.RETAINS_INTERMEDIATES)));

    private static final Map<NodeKind, CostSpec> BY_NODE = byNode();

    private static Map<NodeKind, CostSpec> byNode() {
        Map<NodeKind, CostSpec> m = new EnumMap<>(NodeKind.class);
        // A source node only carries the uploaded bytes forward; it produces nothing of its own.
        m.put(NodeKind.SOURCE, CostSpec.of(1.0, 0));
        m.put(NodeKind.MERGE, CostSpec.of(1.05, 1.5, Trait.AMPLIFIES_PAGES));
        m.put(NodeKind.ARRANGE, CostSpec.of(1.05, 1.5, Trait.AMPLIFIES_PAGES));
        m.put(NodeKind.IMAGES_TO_PDF, CostSpec.of(1.5, 2.0));
        m.put(NodeKind.EXTRACT, CostSpec.of(1.0, 1.5));
        m.put(NodeKind.COMPRESS, CostSpec.of(1.0, 2.5));
        m.put(NodeKind.ROTATE, REWRITE);
        m.put(NodeKind.PROTECT, REWRITE);
        m.put(NodeKind.UNLOCK, REWRITE);
        m.put(NodeKind.METADATA, REWRITE);
        m.put(NodeKind.WATERMARK, REWRITE);
        m.put(NodeKind.CROP, REWRITE);
        m.put(NodeKind.NUP, REWRITE);
        m.put(NodeKind.PAGE_MARKS, REWRITE);
        m.put(NodeKind.REPAIR, REWRITE);
        m.put(NodeKind.OCR, CostSpec.of(1.5, 2.5, Trait.RASTERISES, Trait.EXTERNAL_PROCESS));
        m.put(NodeKind.GDPR_REDACT, CostSpec.of(2.0, 2.0, Trait.RASTERISES));
        m.put(NodeKind.TO_IMAGES, CostSpec.of(0, 1.5, Trait.RASTERISES, Trait.UNBOUNDED_OUTPUT));
        m.put(NodeKind.TO_TEXT, CostSpec.of(0.3, 1.5));
        return Map.copyOf(m);
    }

    /**
     * The declared cost of the endpoint at {@code path}, or {@code null} when the path declares
     * none. A {@code null} here is what the completeness test fails on; callers that need a value
     * at runtime use {@link #forPathOrDefault(String)}.
     */
    public static CostSpec forPath(String path) {
        return path == null ? null : BY_PATH.get(path);
    }

    /**
     * The declared cost of {@code path}, falling back to a plain document rewrite. The fallback
     * exists only so an unclassified path cannot be admitted with an estimate of <em>zero</em>;
     * the completeness test makes sure no shipped endpoint ever relies on it.
     */
    public static CostSpec forPathOrDefault(String path) {
        CostSpec spec = forPath(path);
        return spec != null ? spec : REWRITE;
    }

    /** The declared cost of a pipeline node kind, or {@code null} when the kind declares none. */
    public static CostSpec forNode(NodeKind kind) {
        return kind == null ? null : BY_NODE.get(kind);
    }

    /** As {@link #forNode(NodeKind)} with the same non-zero fallback as {@link #forPathOrDefault}. */
    public static CostSpec forNodeOrDefault(NodeKind kind) {
        CostSpec spec = forNode(kind);
        return spec != null ? spec : REWRITE;
    }
}
