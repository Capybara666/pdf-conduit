package com.pdfconduit.web.ratelimit;

import com.pdfconduit.web.config.WebProperties;
import com.pdfconduit.web.support.ClientIp;
import com.pdfconduit.web.support.Endpoints;
import com.pdfconduit.web.support.JsonErrors;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Per-IP token-bucket rate limiting (bucket4j), the first hardening layer. A GENERAL bucket
 * ({@code ratelimit.burst} capacity, {@code ratelimit.requests-per-minute} refill) meters every
 * non-cheap {@code /api/**} request; a HEAVY bucket ({@code ratelimit.heavy-per-minute}) applies
 * additionally to expensive endpoints. Buckets are kept per client IP in a {@link ConcurrentHashMap}
 * with periodic idle eviction and a hard size cap, so no external store is needed.
 *
 * <p>On exhaustion the request is short-circuited with 429 {@code rate_limited} + {@code Retry-After};
 * otherwise {@code X-RateLimit-Limit}/{@code X-RateLimit-Remaining} (general bucket) are emitted and
 * the chain continues. The whole filter is a no-op when {@code ratelimit.enabled=false}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int MAX_ENTRIES = 100_000;
    private static final long IDLE_TTL_MS = Duration.ofMinutes(30).toMillis();
    private static final long CLEANUP_INTERVAL_MIN = 5;

    private final boolean enabled;
    private final int burst;
    private final int requestsPerMinute;
    private final int heavyPerMinute;

    private final ConcurrentHashMap<String, Buckets> store = new ConcurrentHashMap<>();
    private final ClientIp clientIp;
    private ScheduledExecutorService cleaner;

    public RateLimitFilter(WebProperties props, ClientIp clientIp) {
        WebProperties.RateLimit rl = props.ratelimit();
        this.enabled = rl.enabled();
        this.burst = rl.burst();
        this.requestsPerMinute = rl.requestsPerMinute();
        this.heavyPerMinute = rl.heavyPerMinute();
        this.clientIp = clientIp;
    }

    @PostConstruct
    void startCleaner() {
        if (!enabled) return;
        cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ratelimit-cleanup");
            t.setDaemon(true);
            return t;
        });
        cleaner.scheduleWithFixedDelay(this::evictIdle,
            CLEANUP_INTERVAL_MIN, CLEANUP_INTERVAL_MIN, TimeUnit.MINUTES);
    }

    @PreDestroy
    void stopCleaner() {
        if (cleaner != null) cleaner.shutdownNow();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = Endpoints.path(request);
        if (!enabled || !Endpoints.isMetered(path)) {
            chain.doFilter(request, response);
            return;
        }

        Buckets buckets = bucketsFor(clientIp.resolve(request));

        ConsumptionProbe general = buckets.general.tryConsumeAndReturnRemaining(1);
        response.setHeader("X-RateLimit-Limit", Integer.toString(burst));
        response.setHeader("X-RateLimit-Remaining", Long.toString(Math.max(0, general.getRemainingTokens())));
        if (!general.isConsumed()) {
            reject(response, general.getNanosToWaitForRefill());
            return;
        }

        if (Endpoints.isHeavy(path)) {
            ConsumptionProbe heavy = buckets.heavy.tryConsumeAndReturnRemaining(1);
            if (!heavy.isConsumed()) {
                reject(response, heavy.getNanosToWaitForRefill());
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, long nanosToWait) throws IOException {
        long retryAfter = Math.max(1, (nanosToWait + 999_999_999L) / 1_000_000_000L);
        response.setHeader("Retry-After", Long.toString(retryAfter));
        JsonErrors.write(response, 429, "rate_limited",
            "Too many requests. Please slow down and try again shortly.",
            Map.of("retryAfterSeconds", retryAfter));
    }

    /** Cap the entries scanned per hot-path eviction so a flood can't turn each request into an O(map) sweep. */
    private static final int MAX_EVICT_SCAN = 2_048;

    private Buckets bucketsFor(String ip) {
        // Existing keys never trigger eviction; only admitting a brand-new IP when the map is full
        // does, and then only a bounded scan (the scheduled cleaner does the full sweep off-thread).
        Buckets existing = store.get(ip);
        if (existing != null) {
            existing.lastAccess = System.currentTimeMillis();
            return existing;
        }
        if (store.size() >= MAX_ENTRIES) {
            evictIdleBounded();
            if (store.size() >= MAX_ENTRIES) {
                // Still full after a bounded sweep — shed rather than grow unbounded; reuse a
                // transient bucket that is not retained, so the map stays capped.
                return new Buckets(newGeneral(), newHeavy());
            }
        }
        Buckets b = store.computeIfAbsent(ip, k -> new Buckets(newGeneral(), newHeavy()));
        b.lastAccess = System.currentTimeMillis();
        return b;
    }

    /** Removes idle entries but scans at most {@link #MAX_EVICT_SCAN} of them, bounding per-call cost. */
    private void evictIdleBounded() {
        long cutoff = System.currentTimeMillis() - IDLE_TTL_MS;
        int scanned = 0;
        var it = store.entrySet().iterator();
        while (it.hasNext() && scanned++ < MAX_EVICT_SCAN) {
            if (it.next().getValue().lastAccess < cutoff) it.remove();
        }
    }

    private Bucket newGeneral() {
        Bandwidth limit = Bandwidth.builder()
            .capacity(burst)
            .refillGreedy(requestsPerMinute, Duration.ofMinutes(1))
            .build();
        return Bucket.builder().addLimit(limit).build();
    }

    private Bucket newHeavy() {
        Bandwidth limit = Bandwidth.builder()
            .capacity(heavyPerMinute)
            .refillGreedy(heavyPerMinute, Duration.ofMinutes(1))
            .build();
        return Bucket.builder().addLimit(limit).build();
    }

    private void evictIdle() {
        long cutoff = System.currentTimeMillis() - IDLE_TTL_MS;
        store.entrySet().removeIf(e -> e.getValue().lastAccess < cutoff);
    }

    /** Per-IP buckets plus a last-access stamp for idle eviction. */
    private static final class Buckets {
        final Bucket general;
        final Bucket heavy;
        volatile long lastAccess;

        Buckets(Bucket general, Bucket heavy) {
            this.general = general;
            this.heavy = heavy;
            this.lastAccess = System.currentTimeMillis();
        }
    }
}
