package com.pdfconduit.web.cost;

import com.pdfconduit.core.pipeline.Connection;
import com.pdfconduit.core.pipeline.PipelineGraph;
import com.pdfconduit.core.pipeline.PipelineModel;
import com.pdfconduit.core.pipeline.PipelineNode;
import com.pdfconduit.web.config.WebProperties;
import com.pdfconduit.web.plan.PlanLimits;
import com.pdfconduit.web.plan.RequestPlan;
import com.pdfconduit.web.support.Endpoints;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a declared {@link CostSpec} into the bytes a request is expected to occupy, and owns the
 * two numbers that follow from the server's memory budget.
 *
 * <p><b>The hole this closes.</b> Every other ceiling bounds one dimension in isolation, so the
 * <em>products</em> slip through: bytes × pipeline stages (a 13-node graph over eight legal uploads
 * allocates thirteen times the upload), and per-request budget × concurrency slots (four heavy
 * permits each entitled to a 64 MB result, three copies apiece, does not fit a 1.15 GB heap). Both
 * become one number here, checked before anything is allocated.
 *
 * <h2>The two derived numbers</h2>
 * <ul>
 *   <li>{@link #maxWorkBytes()} — the process-wide pool, {@code concurrency.max-work-bytes}. Every
 *       admitted heavy request reserves its estimate from it, so concurrency is bounded by what the
 *       work actually costs rather than by a slot count that knows nothing about size.</li>
 *   <li>{@link #perRequestOutputBytes(PlanLimits)} — the aggregate result ceiling one request may
 *       accumulate: the configured {@code processing.max-total-output-bytes}, capped at
 *       {@code pool ÷ copies} so no single request is entitled to more than the pool can hold once
 *       the copies made while assembling the response are counted.</li>
 * </ul>
 *
 * <p><b>Why the slot count no longer multiplies.</b> Reserving the estimate is what bounds the sum:
 * four permits no longer imply four times the largest affordable request, because the fourth
 * request is shed when the pool has no room rather than admitted because a slot happened to be
 * free. The per-request cap above is the other half — an entitlement larger than the pool would be
 * a promise the server could not keep even on an idle machine.
 *
 * <p><b>An estimate, not a measurement.</b> The factors in {@link CostCatalog} are coarse on
 * purpose — their job is to refuse the impossible in milliseconds. Everything admitted is still
 * held to the exact running tallies in {@link com.pdfconduit.web.guard.OutputBudget}, which remain
 * the authority on what a request may actually produce.
 */
@Component
public class CostModel {

    /**
     * How many copies of the result set coexist while a response is assembled: the results
     * themselves, the in-memory ZIP buffer built from them, and the response body handed to Tomcat.
     */
    public static final int RESULT_COPIES = 3;

    /** A4 in inches — the page a raster-cost estimate assumes when it has not parsed the file yet. */
    private static final double A4_WIDTH_IN = 8.27;
    private static final double A4_HEIGHT_IN = 11.69;

    /** Bytes per pixel of a decoded ARGB raster, which is what PDFBox renders into. */
    private static final int RASTER_BYTES_PER_PIXEL = 4;

    private final RequestPlan requestPlan;
    private final long maxWorkBytes;

    public CostModel(WebProperties props, RequestPlan requestPlan) {
        this.requestPlan = requestPlan;
        this.maxWorkBytes = props.concurrency().maxWorkBytes();
    }

    /** The process-wide ceiling on the summed estimated cost of all concurrent heavy work. */
    public long maxWorkBytes() {
        return maxWorkBytes;
    }

    /**
     * The aggregate result ceiling for one request under {@code plan}: the configured
     * {@code processing.max-total-output-bytes}, never above {@code pool ÷ copies} — the largest
     * result the memory pool could hold while the response is assembled. Both the pre-flight
     * estimate and the running byte tally use this one number, so the budget a request is admitted
     * on is the budget it is held to.
     */
    public long perRequestOutputBytes(PlanLimits plan) {
        long affordable = maxWorkBytes / RESULT_COPIES;
        long configured = plan.maxTotalOutputBytes();
        if (configured <= 0) return affordable;
        return Math.min(configured, affordable);
    }

    /** As {@link #perRequestOutputBytes(PlanLimits)} for the plan in force on this thread. */
    public long perRequestOutputBytes() {
        return perRequestOutputBytes(requestPlan.current());
    }

    /**
     * The estimate for the request being handled on this thread, from the cost its endpoint
     * declares. Called by {@link com.pdfconduit.web.guard.LoadGuard} for every heavy operation, so
     * an endpoint cannot be admitted without one.
     */
    public WorkEstimate forCurrentRequest(long inputBytes) {
        return forPath(currentPath(), inputBytes);
    }

    /** The estimate for {@code path} carrying {@code inputBytes} of uploads. */
    public WorkEstimate forPath(String path, long inputBytes) {
        return forSpec(CostCatalog.forPathOrDefault(path), inputBytes);
    }

    /** The estimate for a single-stage unit of work: nothing is retained between stages. */
    public WorkEstimate forSpec(CostSpec spec, long inputBytes) {
        PlanLimits plan = requestPlan.current();
        long budget = perRequestOutputBytes(plan);
        long produced = producedBytes(spec, inputBytes, budget);
        return new WorkEstimate(inputBytes, 0, produced * RESULT_COPIES,
            workingBytes(spec, inputBytes, plan));
    }

    /**
     * The estimate for a client-supplied pipeline graph — the case a per-endpoint factor cannot
     * express, because the multiplier is the graph itself.
     *
     * <p>{@link com.pdfconduit.core.pipeline.PipelineExecutor} keeps every node's outputs in a map
     * for the whole run (a later node may consume any earlier one), so the run's cost is the
     * <em>sum</em> over the graph, not the size of its largest stage. Walking the graph in
     * topological order gives that sum from the resolved source bytes alone, with no document
     * parsed and nothing allocated — which is the only place a 13-stage chain over eight legal
     * uploads can be caught.
     *
     * @param model            the parsed graph
     * @param sourceBytesByNode resolved upload bytes per source node id (a name listed twice counts
     *                          twice, exactly as the executor will process it twice)
     * @param extraInputBytes  uploads that ride along without entering the graph as documents
     *                         (a watermark node's image asset)
     */
    public WorkEstimate forPipeline(PipelineModel model, Map<String, Long> sourceBytesByNode,
                                    long extraInputBytes) {
        PlanLimits plan = requestPlan.current();
        long budget = perRequestOutputBytes(plan);
        long sourceTotal = Math.max(0, extraInputBytes);
        for (Long b : sourceBytesByNode.values()) sourceTotal += Math.max(0, b);

        List<PipelineNode> order;
        try {
            order = PipelineGraph.topologicalOrder(model);
        } catch (PipelineGraph.CycleException e) {
            // A cycle is rejected by the validator inside the run; for the estimate, assume every
            // node retains a full copy of the sources — an upper bound that never under-charges.
            long nodes = model.nodes == null ? 0 : model.nodes.size();
            return new WorkEstimate(sourceTotal, sourceTotal * nodes, budget * RESULT_COPIES,
                workingBytes(CostSpec.of(0, 1.5), sourceTotal, plan));
        }

        Map<String, Long> outputs = new HashMap<>();
        long intermediates = 0;
        long results = 0;
        long working = 0;
        for (PipelineNode n : order) {
            if (n.kind == null) continue;
            if (n.kind.isSource()) {
                outputs.put(n.id, Math.max(0, sourceBytesByNode.getOrDefault(n.id, 0L)));
                continue;
            }
            long in = 0;
            for (Connection c : model.incoming(n.id)) in += outputs.getOrDefault(c.fromNodeId(), 0L);
            CostSpec spec = CostCatalog.forNodeOrDefault(n.kind);
            long out = producedBytes(spec, in, budget);
            outputs.put(n.id, out);
            working = Math.max(working, workingBytes(spec, in, plan));
            if (model.isTerminal(n)) results += out;
            else intermediates += out;
        }
        return new WorkEstimate(sourceTotal, intermediates, results * RESULT_COPIES, working);
    }

    /**
     * What one stage hands on. An operation whose output tracks its input is charged that
     * multiple; a rasteriser (or any other {@link CostSpec.Trait#UNBOUNDED_OUTPUT} producer) is
     * charged the full per-request budget, because nothing smaller bounds it — that entitlement is
     * exactly what the running tally will later enforce.
     */
    private static long producedBytes(CostSpec spec, long inputBytes, long budget) {
        if (spec.unboundedOutput()) return budget;
        long proportional = Math.round(Math.max(0, inputBytes) * spec.outputFactor());
        return budget > 0 ? Math.min(proportional, budget) : proportional;
    }

    /**
     * Transient heap on top of what is retained: the parsed documents, plus — for a rasteriser —
     * one page decoded at the plan's DPI ceiling, which is a real, large, and easily forgotten
     * allocation (an A4 page at 300 DPI is ~35 MB before it is encoded).
     */
    private static long workingBytes(CostSpec spec, long inputBytes, PlanLimits plan) {
        long working = Math.round(Math.max(0, inputBytes) * spec.workingFactor());
        if (spec.rasterises()) working += rasterPageBytes(plan);
        return working;
    }

    /** One decoded page at the plan's DPI ceiling, never above its per-page pixel ceiling. */
    private static long rasterPageBytes(PlanLimits plan) {
        int dpi = plan.maxDpi() > 0 ? plan.maxDpi() : 300;
        long pixels = Math.round(A4_WIDTH_IN * dpi * A4_HEIGHT_IN * dpi);
        if (plan.maxOutputPixels() > 0) pixels = Math.min(pixels, plan.maxOutputPixels());
        return pixels * RASTER_BYTES_PER_PIXEL;
    }

    /** The path of the request bound to this thread, or {@code null} outside a request. */
    private static String currentPath() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servlet)) return null;
        HttpServletRequest request = servlet.getRequest();
        return request == null ? null : Endpoints.path(request);
    }
}
