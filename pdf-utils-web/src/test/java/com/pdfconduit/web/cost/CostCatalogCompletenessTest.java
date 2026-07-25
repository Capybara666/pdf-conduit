package com.pdfconduit.web.cost;

import com.pdfconduit.core.pipeline.NodeKind;
import com.pdfconduit.web.support.Endpoints;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The test that keeps the cost model alive after whoever wrote it has moved on.
 *
 * <p>{@code EndpointsClassificationTest} already forces every POST under {@code /api} to declare
 * <em>which tier</em> it belongs to. That is not enough on its own: a tier says an endpoint is
 * guarded, not what it will cost, and admission is decided on cost. An endpoint or a pipeline node
 * that ships without a declared {@link CostSpec} would be estimated from a fallback that knows
 * nothing about it — a new rasteriser charged as a plain document rewrite, a new page multiplier
 * charged as if its output matched its input — and the products would start slipping through again.
 *
 * <p>So both catalogs are enumerated from the live system: the endpoints from Spring's actual
 * handler mappings, the node kinds from the enum the executor switches on.
 */
@SpringBootTest
class CostCatalogCompletenessTest {

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    /** Every POST-accepting path under {@code /api}, from the actual handler mappings. */
    private Set<String> postApiPaths() {
        Set<String> paths = new TreeSet<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            boolean acceptsPost = methods.isEmpty() || methods.contains(RequestMethod.POST);
            if (!acceptsPost) continue;
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                if (Endpoints.isApi(pattern)) paths.add(pattern);
            }
        }
        return paths;
    }

    @Test
    void everyClassifiedPostEndpoint_declaresItsCost() {
        Set<String> posts = postApiPaths();
        assertTrue(posts.contains("/api/merge"),
            "handler scan found no /api/merge — mapping enumeration is broken, got: " + posts);

        List<String> undeclared = new ArrayList<>();
        for (String path : posts) {
            if (!Endpoints.isClassified(path)) continue;   // EndpointsClassificationTest owns that
            if (CostCatalog.forPath(path) == null) undeclared.add(path);
        }
        assertTrue(undeclared.isEmpty(),
            "POST /api endpoint(s) with no declared cost — each would be admitted on a guess "
                + "instead of an estimate. Add an entry to CostCatalog saying how it scales "
                + "(output vs input, working set, and whether it rasterises, amplifies pages or "
                + "shells out): " + undeclared);
    }

    @Test
    void everyPipelineNodeKind_declaresItsCost() {
        List<NodeKind> undeclared = new ArrayList<>();
        for (NodeKind kind : NodeKind.values()) {
            if (CostCatalog.forNode(kind) == null) undeclared.add(kind);
        }
        assertTrue(undeclared.isEmpty(),
            "pipeline node kind(s) with no declared cost — a graph containing one would be "
                + "estimated as if it were a plain document rewrite, which is exactly how a "
                + "rasterising or page-multiplying node slips past admission. Add an entry to "
                + "CostCatalog: " + undeclared);
    }

    /**
     * A rasterising endpoint and its pipeline node must agree that they rasterise. They reach the
     * same core operation, so a node declared cheaper than its endpoint twin is a way to buy the
     * expensive operation at the cheap price.
     */
    @Test
    void rasterisingNodesAndTheirEndpointTwinsAgree() {
        assertTrue(CostCatalog.forNode(NodeKind.TO_IMAGES).rasterises()
                && CostCatalog.forPath("/api/to-images").rasterises(),
            "TO_IMAGES rasterises on both surfaces");
        assertTrue(CostCatalog.forNode(NodeKind.OCR).rasterises()
                && CostCatalog.forPath("/api/ocr").rasterises(),
            "OCR rasterises on both surfaces");
        assertTrue(CostCatalog.forNode(NodeKind.GDPR_REDACT).rasterises()
                && CostCatalog.forPath("/api/auto-redact").rasterises(),
            "the GDPR scan→redact hand-off rasterises on both surfaces");
    }

    /**
     * Every declared cost must actually charge for something. A spec of all zeros on a real
     * operation is indistinguishable from having declared nothing at all, so the catalog entry
     * would satisfy the completeness check while leaving the endpoint admitted for free.
     */
    @Test
    void operationCostsAreNonZero() {
        for (String path : postApiPaths()) {
            if (!Endpoints.isHeavy(path)) continue;
            CostSpec spec = CostCatalog.forPath(path);
            assertNotNull(spec, path);
            assertTrue(spec.outputFactor() > 0 || spec.workingFactor() > 0 || !spec.traits().isEmpty(),
                path + " is a heavy operation but declares no cost at all");
        }
        for (NodeKind kind : NodeKind.values()) {
            if (kind.isSource()) continue;
            CostSpec spec = CostCatalog.forNode(kind);
            assertTrue(spec.outputFactor() > 0 || spec.workingFactor() > 0 || !spec.traits().isEmpty(),
                kind + " declares no cost at all");
        }
    }
}
