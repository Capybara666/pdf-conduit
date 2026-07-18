package com.pdfconduit.web.quota;

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
 * In-process {@link QuotaStore}: per-key daily operation counters held in a {@link
 * ConcurrentHashMap}. Counters roll over at UTC midnight (the first access on a new calendar day
 * resets the count to zero); a bounded hot-path sweep plus an hourly scheduled sweep cap the map so
 * a flood of distinct keys cannot grow it without bound. This is the only implementation today and
 * carries the exact logic the former {@code QuotaService} held; a shared-store implementation would
 * replace it to make the quota correct across multiple backend instances.
 */
@Component
public class InMemoryQuotaStore implements QuotaStore {

    private static final int MAX_ENTRIES = 100_000;

    private final ConcurrentHashMap<String, DayCount> store = new ConcurrentHashMap<>();
    private ScheduledExecutorService cleaner;

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

    /** Operations already used by {@code key} today (after rolling over if the day changed). */
    @Override
    public long used(String key) {
        return entry(key).count.get();
    }

    /** Counts one successful operation for {@code key}. */
    @Override
    public void increment(String key) {
        entry(key).count.incrementAndGet();
    }

    /** Epoch seconds of the next reset (next UTC midnight). */
    @Override
    public long resetEpochSeconds() {
        return LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
    }

    /** Cap the entries scanned per hot-path eviction so a flood can't turn each request into an O(map) sweep. */
    private static final int MAX_EVICT_SCAN = 2_048;

    private DayCount entry(String key) {
        long today = LocalDate.now(ZoneOffset.UTC).toEpochDay();
        DayCount existing = store.get(key);
        if (existing == null && store.size() >= MAX_ENTRIES) {
            evictStaleBounded(today);
            if (store.size() >= MAX_ENTRIES) {
                // Still full after a bounded sweep — return a transient counter that is not retained
                // so the map stays capped (the scheduled hourly cleaner does the full sweep).
                return new DayCount(today);
            }
        }
        DayCount dc = store.computeIfAbsent(key, k -> new DayCount(today));
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
