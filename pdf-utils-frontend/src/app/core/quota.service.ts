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
  /** Latest snapshot; null until the first API response arrives. */
  readonly snapshot = signal<QuotaSnapshot | null>(null);

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

  /** Merge any quota headers present on a response into the current snapshot. */
  update(headers: HttpHeaders): void {
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
    apply('X-Quota-Limit', 'quotaLimit');
    apply('X-Quota-Remaining', 'quotaRemaining');
    apply('X-Quota-Reset', 'quotaReset');

    if (changed) {
      this.snapshot.set(next);
    }
  }
}
