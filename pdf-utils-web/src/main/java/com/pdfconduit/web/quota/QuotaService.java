package com.pdfconduit.web.quota;

import com.pdfconduit.web.config.WebProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks the free-tier daily operation count per client IP, entirely in memory. Counters roll over
 * at UTC midnight: the first access on a new calendar day resets the count to zero. Only successful
 * operations are counted (the interceptor increments in {@code afterCompletion} on a 2xx), so failed
 * requests never burn quota.
 */
@Component
public class QuotaService {

    private static final int MAX_ENTRIES = 100_000;

    private final int dailyLimit;
    private final ConcurrentHashMap<String, DayCount> store = new ConcurrentHashMap<>();
    private ScheduledExecutorService cleaner;

    public QuotaService(WebProperties props) {
        this.dailyLimit = props.quota().dailyOperations();
    }

    @PostConstruct
    void startCleaner() {
        cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "quota-cleanup");
            t.setDaemon(true);
            return t;
        });
        // Sweep stale (previous-day) counters hourly to bound the map.
        cleaner.scheduleWithFixedDelay(this::evictStale, 1, 1, TimeUnit.HOURS);
    }

    @PreDestroy
    void stopCleaner() {
        if (cleaner != null) cleaner.shutdownNow();
    }

    public int dailyLimit() {
        return dailyLimit;
    }

    /** Operations already used by {@code ip} today (after rolling over if the day changed). */
    public long used(String ip) {
        return entry(ip).count.get();
    }

    /** Remaining free operations for {@code ip} today. */
    public long remaining(String ip) {
        return Math.max(0, dailyLimit - used(ip));
    }

    /** True when {@code ip} has already consumed its full daily allowance. */
    public boolean isExhausted(String ip) {
        return used(ip) >= dailyLimit;
    }

    /** Counts one successful operation for {@code ip}. */
    public void increment(String ip) {
        entry(ip).count.incrementAndGet();
    }

    /** Epoch seconds of the next reset (next UTC midnight). */
    public long resetEpochSeconds() {
        return LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
    }

    /** Cap the entries scanned per hot-path eviction so a flood can't turn each request into an O(map) sweep. */
    private static final int MAX_EVICT_SCAN = 2_048;

    private DayCount entry(String ip) {
        long today = LocalDate.now(ZoneOffset.UTC).toEpochDay();
        DayCount existing = store.get(ip);
        if (existing == null && store.size() >= MAX_ENTRIES) {
            evictStaleBounded(today);
            if (store.size() >= MAX_ENTRIES) {
                // Still full after a bounded sweep — return a transient counter that is not retained
                // so the map stays capped (the scheduled hourly cleaner does the full sweep).
                return new DayCount(today);
            }
        }
        DayCount dc = store.computeIfAbsent(ip, k -> new DayCount(today));
        if (dc.epochDay != today) {
            synchronized (dc) {
                if (dc.epochDay != today) {
                    dc.epochDay = today;
                    dc.count.set(0);
                }
            }
        }
        return dc;
    }

    private void evictStale() {
        long today = LocalDate.now(ZoneOffset.UTC).toEpochDay();
        store.entrySet().removeIf(e -> e.getValue().epochDay != today);
    }

    /** Removes stale (previous-day) counters but scans at most {@link #MAX_EVICT_SCAN}, bounding per-call cost. */
    private void evictStaleBounded(long today) {
        int scanned = 0;
        var it = store.entrySet().iterator();
        while (it.hasNext() && scanned++ < MAX_EVICT_SCAN) {
            if (it.next().getValue().epochDay != today) it.remove();
        }
    }

    private static final class DayCount {
        volatile long epochDay;
        final AtomicLong count = new AtomicLong();

        DayCount(long epochDay) {
            this.epochDay = epochDay;
        }
    }
}
