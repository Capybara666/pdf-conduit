package com.pdfconduit.web;

import com.pdfconduit.web.config.WebProperties;
import com.pdfconduit.web.cost.CostModel;
import com.pdfconduit.web.cost.WorkEstimate;
import com.pdfconduit.web.error.OutputTooLargeException;
import com.pdfconduit.web.error.ProcessingTimeoutException;
import com.pdfconduit.web.error.ServerBusyException;
import com.pdfconduit.web.guard.LoadGuard;
import com.pdfconduit.web.observability.WebMetrics;
import com.pdfconduit.web.plan.FreePlanLimitsResolver;
import com.pdfconduit.web.plan.PlanLimitsResolver;
import com.pdfconduit.web.plan.RequestPlan;
import com.pdfconduit.web.principal.IpPrincipal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The <b>product across concurrent requests</b> hole: a per-request budget and a concurrency slot
 * count that are tuned independently and then multiply. Four heavy permits, each request entitled
 * to a 64 MB result held in roughly three copies while the response is assembled, is ~768 MB of
 * results alone — and nothing anywhere added those numbers up, so four perfectly legitimate
 * requests could be admitted into a heap that fits two or three.
 *
 * <p>What closes it is that admission is charged against one process-wide pool: a request reserves
 * what it is estimated to cost, and the reservation — not the free slot — decides whether it runs.
 * These tests assert the two halves of that: the sum of admitted work never exceeds the pool, and a
 * request that could not fit even on an idle server is refused outright rather than shed.
 */
class WorkBudgetTest {

    private static final long MB = 1024 * 1024;

    /** A pool of 300 MB with FOUR permits, so the permits can never be the binding constraint. */
    private static WebProperties props() {
        return props(null);
    }

    /** The same pool, with an explicit processing timeout for the load-shedding case. */
    private static WebProperties props(Integer timeoutSeconds) {
        return new WebProperties(null, null, null, null, null, null, null,
            new WebProperties.Concurrency(4, 300 * MB, null),
            timeoutSeconds == null ? null : new WebProperties.Processing(timeoutSeconds, null),
            null, null, null, null);
    }

    private static LoadGuard loadGuard(WebProperties props) {
        PlanLimitsResolver plans = new FreePlanLimitsResolver(props);
        RequestPlan requestPlan = new RequestPlan(request -> new IpPrincipal("test"), plans);
        return new LoadGuard(props, new WebMetrics(new SimpleMeterRegistry()), requestPlan,
            new CostModel(props, requestPlan));
    }

    private static WorkEstimate costing(long bytes) {
        return new WorkEstimate(bytes, 0, 0, 0);
    }

    /**
     * Three 100 MB admissions fill a 300 MB pool; the fourth is shed even though a permit is free.
     * Before the pool existed, the slot count alone decided this and all four ran.
     */
    @Test
    void concurrentAdmissionsAreBoundedByTheSharedPool() throws Exception {
        LoadGuard guard = loadGuard(props());
        CountDownLatch admitted = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger running = new AtomicInteger();

        for (int i = 0; i < 3; i++) {
            Thread t = new Thread(() -> {
                try {
                    guard.execute(costing(100 * MB), () -> {
                        running.incrementAndGet();
                        admitted.countDown();
                        awaitQuietly(release);
                        return "ok";
                    });
                } catch (Exception ignored) {
                    // A failure here shows up as a missed countdown on `admitted` below.
                }
            });
            t.setDaemon(true);
            t.start();
        }
        assertTrue(admitted.await(10, TimeUnit.SECONDS), "three 100 MB tasks should fill the pool");

        assertThrows(ServerBusyException.class,
            () -> guard.execute(costing(100 * MB), () -> "fourth"),
            "the fourth 100 MB task would take the pool to 400 MB and must be shed, even though a "
                + "fourth permit is free");
        assertEquals(3, running.get(), "no extra task may have started");
        assertTrue(guard.inFlightBytes() <= guard.maxWorkBytes(),
            "reserved work must never exceed the pool");

        release.countDown();
    }

    /**
     * Once the pool frees up, the same request is admitted — the shed is transient, not a verdict.
     *
     * <p>Two 250 MB requests do not fit in a 300 MB pool <em>together</em>, so this only passes if
     * the first reservation is already gone by the time its caller has its result in hand. That is
     * the guarantee the worker gives by releasing before it publishes the result, and it is not
     * academic: a client issuing its next request the instant the previous response lands would
     * otherwise be shed by its own finished work.
     */
    @Test
    void poolIsReleasedWhenWorkFinishes() throws Exception {
        LoadGuard guard = loadGuard(props());
        assertEquals("first", guard.execute(costing(250 * MB), () -> "first"));
        assertEquals(0, guard.inFlightBytes(), "a finished request must give its reservation back");
        assertEquals("second", guard.execute(costing(250 * MB), () -> "second"));
        assertEquals(0, guard.inFlightBytes(), "a finished request must give its reservation back");
    }

    /**
     * The other half of that rule, and the one it must never be traded for: the slot follows the
     * WORK, not the caller. A task that outruns the processing timeout sheds its client with 503,
     * but keeps its permit and its reserved bytes until it genuinely ends — that is what stops
     * stuck work from piling up behind a caller-side release. Releasing on the caller's way out
     * would make this test admit the second request into a pool that is still occupied.
     */
    @Test
    void aShedCallerDoesNotHandBackASlotTheWorkStillHolds() throws Exception {
        LoadGuard guard = loadGuard(props(1));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);

        assertThrows(ProcessingTimeoutException.class, () -> guard.execute(costing(250 * MB), () -> {
            started.countDown();
            // Deliberately deaf to the timeout's best-effort interrupt, exactly like the PDFBox
            // work this guard exists to contain: the task ends when it ends, not when asked.
            awaitIgnoringInterrupts(finish);
            return "still running";
        }), "work past the timeout must shed its caller");
        assertTrue(started.await(10, TimeUnit.SECONDS), "the task must actually have started");

        assertEquals(250 * MB, guard.inFlightBytes(),
            "a shed caller must not give back bytes the running task is still using");
        assertEquals(3, guard.availablePermits(),
            "a shed caller must not give back the permit the running task is still holding");
        assertThrows(ServerBusyException.class, () -> guard.execute(costing(250 * MB), () -> "no"),
            "the pool is still occupied by the runaway task, so new work is shed");

        finish.countDown();  // the release is the task's to make, and only once it truly ends
        assertTrue(awaitPoolDrained(guard), "the slot must come back when the work finally ends");
        assertEquals(4, guard.availablePermits());
    }

    /**
     * Waits out the one release that IS asynchronous: a task whose caller already walked away has
     * no one to hand its result to, so its release is only observable eventually.
     */
    private static boolean awaitPoolDrained(LoadGuard guard) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (guard.inFlightBytes() == 0) return true;
            Thread.sleep(5);
        }
        return false;
    }

    /**
     * A request that cannot fit even on a completely idle server is a property of the request, so
     * it is refused outright (422) rather than shed as "busy" — retrying would never help.
     */
    @Test
    void aRequestBiggerThanTheWholePoolIsRefusedOutright() {
        LoadGuard guard = loadGuard(props());
        OutputTooLargeException e = assertThrows(OutputTooLargeException.class,
            () -> guard.execute(costing(600 * MB), () -> "never runs"));
        assertTrue(e.getMessage().contains("MB of memory"), e.getMessage());
        assertEquals(0, guard.inFlightBytes(), "a refused request must reserve nothing");
        assertEquals(4, guard.availablePermits(), "a refused request must not consume a permit");
    }

    /**
     * The per-request result budget can never promise more than the pool holds once the copies made
     * while assembling the response are counted — which is what stops a generous
     * {@code max-total-output-bytes} from being multiplied by the slot count.
     */
    @Test
    void perRequestOutputBudgetIsCappedByThePool() {
        WebProperties props = props();
        PlanLimitsResolver plans = new FreePlanLimitsResolver(props);
        RequestPlan requestPlan = new RequestPlan(request -> new IpPrincipal("test"), plans);
        CostModel costs = new CostModel(props, requestPlan);

        long granted = costs.perRequestOutputBytes(plans.resolveDefault());
        assertTrue(granted * CostModel.RESULT_COPIES <= costs.maxWorkBytes(),
            "a request may not be entitled to more result bytes than the pool can hold in "
                + CostModel.RESULT_COPIES + " copies");
        // The configured ceiling (64 MB) is the smaller of the two here, so it is what is granted.
        assertEquals(64 * MB, granted);
    }

    /** Waits for the latch and refuses to be interrupted out of it, clearing the flag on the way out. */
    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            try {
                if (latch.await(50, TimeUnit.MILLISECONDS)) break;
            } catch (InterruptedException ignored) {
                // the point of the test: this task does not stop just because it was asked to
            }
        }
        Thread.interrupted();   // don't leave the flag set on a pooled thread
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
