import { HttpHeaders } from '@angular/common/http';
import { Injectable, computed, signal } from '@angular/core';

import { QuotaSnapshot } from './api.models';

/**
 * Holds the latest rate-limit / daily-quota snapshot parsed from API response
 * headers. `ApiService` calls `update(headers)` on every response (success or
 * error); the header quota chip and the friendly error copy read the signals.
 *
 * Purely presentational — the backend remains the source of truth and enforces
 * the real limits; this is only a hint so the UI can nudge toward Pro.
 */
@Injectable({ providedIn: 'root' })
export class QuotaService {
  /** localStorage key holding the last-seen quota snapshot (rehydrated on load). */
  private static readonly STORAGE_KEY = 'pdf-conduit.quota';

  /** Latest snapshot; rehydrated from localStorage on load, else null. */
  readonly snapshot = signal<QuotaSnapshot | null>(this.restore());

  /** Free operations remaining today, or null if the header was absent. */
  readonly remaining = computed(() => this.snapshot()?.quotaRemaining ?? null);
  /** Free operations allowed per day, or null if unknown. */
  readonly limit = computed(() => this.snapshot()?.quotaLimit ?? null);

  /** True once we know the daily quota and it is fully spent. */
  readonly exhausted = computed(() => {
    const r = this.remaining();
    return r != null && r <= 0;
  });

  /** True when few free operations remain (≤ 3, or ≤ 20% of the daily cap). */
  readonly low = computed(() => {
    const r = this.remaining();
    if (r == null) return false;
    const lim = this.limit();
    const threshold = lim != null ? Math.max(3, Math.ceil(lim * 0.2)) : 3;
    return r > 0 && r <= threshold;
  });

  /**
   * Merge any quota / rate-limit headers present on a response into the current
   * snapshot.
   *
   * The backend writes `X-Quota-Remaining` optimistically in `preHandle` on
   * *every* request, but only actually charges quota on a 2xx. So the daily
   * quota headers (`X-Quota-*`) must only be trusted on success or on an
   * authoritative `quota_exceeded` (0 remaining) response — otherwise a
   * rejected upload (422/413/415/…) would make the chip drop as if it had cost
   * a free credit. Rate-limit headers (`X-RateLimit-*`) are always applied.
   *
   * Pass `{ quota: false }` to apply only the rate-limit headers.
   */
  update(headers: HttpHeaders, opts: { quota?: boolean } = {}): void {
    const includeQuota = opts.quota ?? true;
    const next: QuotaSnapshot = { ...(this.snapshot() ?? {}) };
    let changed = false;

    const apply = (header: string, key: keyof QuotaSnapshot) => {
      const raw = headers.get(header);
      if (raw != null && raw !== '') {
        const num = Number(raw);
        if (Number.isFinite(num)) {
          next[key] = num;
          changed = true;
        }
      }
    };

    apply('X-RateLimit-Limit', 'rateLimit');
    apply('X-RateLimit-Remaining', 'rateRemaining');
    if (includeQuota) {
      apply('X-Quota-Limit', 'quotaLimit');
      apply('X-Quota-Remaining', 'quotaRemaining');
      apply('X-Quota-Reset', 'quotaReset');
    }

    if (changed) {
      this.snapshot.set(next);
      this.persist(next);
    }
  }

  /**
   * Rehydrate the last-seen snapshot so the header quota chip can render a
   * sensible value on the very first paint after a page refresh — otherwise the
   * count stays hidden until the first operation response arrives (the backend
   * only emits `X-Quota-*` on operation POSTs, never on a plain page load).
   *
   * The next real API response overwrites this hint. A snapshot whose daily
   * quota window has already elapsed (`quotaReset` in the past) is treated as a
   * fresh day: remaining is reset to the daily limit so we never persist a stale
   * "0 left" across the reset boundary.
   */
  private restore(): QuotaSnapshot | null {
    try {
      const raw = localStorage.getItem(QuotaService.STORAGE_KEY);
      if (!raw) return null;
      const snap = JSON.parse(raw) as QuotaSnapshot;
      if (!snap || typeof snap !== 'object') return null;
      if (
        snap.quotaReset != null &&
        snap.quotaLimit != null &&
        Date.now() / 1000 >= snap.quotaReset
      ) {
        snap.quotaRemaining = snap.quotaLimit;
      }
      return snap;
    } catch {
      return null;
    }
  }

  private persist(snap: QuotaSnapshot): void {
    try {
      localStorage.setItem(QuotaService.STORAGE_KEY, JSON.stringify(snap));
    } catch {
      // localStorage may be unavailable (private mode); ignore.
    }
  }
}
