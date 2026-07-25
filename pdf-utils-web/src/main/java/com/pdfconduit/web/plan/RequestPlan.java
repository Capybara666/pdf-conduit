package com.pdfconduit.web.plan;

import com.pdfconduit.web.principal.PrincipalResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The {@link PlanLimits} in force <b>for the current request</b> — the service-layer counterpart of
 * {@link com.pdfconduit.web.quota.UploadCaps}, which already resolves the upload ceilings this way.
 *
 * <p><strong>Why this exists.</strong> The page-count and raster-render ceilings used to be read
 * once in the constructors of the singleton beans that enforce them
 * ({@code WebOperations}, {@code PipelineLimitsGuard}), which made them process-wide: a future paid
 * plan could never raise them for its own caller, and the monetization seam
 * ({@link PlanLimitsResolver} + {@link com.pdfconduit.web.principal.PrincipalResolver}) was dead for
 * exactly the three limits a paid tier would most want to move. Resolving here instead keeps the
 * guards' logic identical while making every ceiling a per-caller value.
 *
 * <p><strong>Two threads, one plan.</strong> Heavy work does not run on the request thread — {@code
 * LoadGuard} hands it to a bounded executor — so a thread-bound lookup alone would silently fall
 * back to the default plan for precisely the operations the ceilings protect. So the resolved plan
 * (an immutable value, never the request object, which Tomcat may recycle the moment a timed-out
 * caller is shed) is captured on the request thread and {@link #bind bound} onto the worker for the
 * duration of the task. {@link #current()} therefore answers the same thing on both threads:
 * <ol>
 *   <li>the plan explicitly bound to this thread (the load-guard worker), else</li>
 *   <li>the plan of the servlet request bound to this thread by Spring, else</li>
 *   <li>{@link PlanLimitsResolver#resolveDefault()} — no request in scope at all (startup, tests).</li>
 * </ol>
 *
 * <p>Resolution is memoised per request (a request attribute), so one request always guards against
 * one plan no matter how many files or pages it touches.
 */
@Component
public class RequestPlan {

    /** Request attribute holding this request's resolved plan (resolve once, guard many times). */
    private static final String ATTRIBUTE = RequestPlan.class.getName() + ".plan";

    /** The plan bound to a load-guard worker thread for the duration of one task. */
    private static final ThreadLocal<PlanLimits> BOUND = new ThreadLocal<>();

    private final PrincipalResolver principals;
    private final PlanLimitsResolver plans;

    public RequestPlan(PrincipalResolver principals, PlanLimitsResolver plans) {
        this.principals = principals;
        this.plans = plans;
    }

    /** The entitlements of the caller behind {@code request} (memoised on the request). */
    public PlanLimits forRequest(HttpServletRequest request) {
        Object cached = request.getAttribute(ATTRIBUTE);
        if (cached instanceof PlanLimits plan) return plan;
        PlanLimits plan = plans.resolve(principals.resolve(request));
        request.setAttribute(ATTRIBUTE, plan);
        return plan;
    }

    /**
     * The entitlements in force on this thread: the bound plan (load-guard worker), else the plan of
     * the request Spring bound to this thread, else the no-principal default. This is what the
     * guards call, and — via {@link #forRequest} — exactly what {@code GET /api/capabilities}
     * advertises, so the advertised ceiling cannot drift from the enforced one.
     */
    public PlanLimits current() {
        PlanLimits bound = BOUND.get();
        if (bound != null) return bound;
        HttpServletRequest request = servletRequest();
        return request != null ? forRequest(request) : plans.resolveDefault();
    }

    /**
     * Binds an already-resolved plan to the calling thread. Used by {@code LoadGuard} to carry the
     * request's plan onto the worker that actually runs the operation; always paired with
     * {@link #unbind()} in a {@code finally}.
     */
    public static void bind(PlanLimits plan) {
        if (plan == null) BOUND.remove();
        else BOUND.set(plan);
    }

    /** Clears whatever {@link #bind} put on this thread (pooled threads outlive the request). */
    public static void unbind() {
        BOUND.remove();
    }

    private static HttpServletRequest servletRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
    }
}
