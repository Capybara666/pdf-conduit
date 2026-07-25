import { Signal, signal } from '@angular/core';
import { Observable, Subscription } from 'rxjs';

import { ApiError, RunResult } from './api.models';
import { RunTracker, runTrackerOf } from './run-progress';

/**
 * Tiny reactive holder for the loading / error / result lifecycle every
 * operation page shares. Pages create one, pass its signals to
 * `<app-op-progress>` (the wait) and `<app-result-panel>` (the outcome), and
 * call `run(...)` with the ApiService observable.
 *
 * It also owns the subscription, which is what makes cancelling possible:
 * unsubscribing aborts the in-flight XHR and returns the page to a clean,
 * retryable state — no stale spinner, no half-populated result.
 */
export class OperationState {
  readonly loading = signal(false);
  readonly error = signal<ApiError | null>(null);
  readonly result = signal<RunResult | null>(null);

  private readonly _tracker = signal<RunTracker | null>(null);
  /**
   * Progress of the current (or just-cancelled) request. Stays set after a
   * cancel so the page can explain what cancelling did and did not do; cleared
   * by `reset()`, `dismiss()` or the next `run()`.
   */
  readonly tracker: Signal<RunTracker | null> = this._tracker.asReadonly();

  private sub: Subscription | null = null;

  /** Return to idle: abort anything in flight and clear loading, error and result. */
  reset(): void {
    this.abort();
    this._tracker.set(null);
    this.loading.set(false);
    this.error.set(null);
    this.result.set(null);
  }

  /** Subscribe to an operation observable and mirror it into the signals. */
  run(obs: Observable<RunResult>, onSuccess?: (r: RunResult) => void): void {
    this.abort();
    // An ApiService operation carries its own tracker (upload bytes and phase);
    // anything else still gets one so the wait is visible — indeterminate and
    // labelled as processing, since nothing measurable is known about it.
    const attached = runTrackerOf(obs);
    if (!attached) {
      const fallback = new RunTracker();
      fallback.processing();
      this._tracker.set(fallback);
    } else {
      this._tracker.set(attached);
    }
    this.loading.set(true);
    this.error.set(null);
    this.result.set(null);
    this.sub = obs.subscribe({
      next: (r) => {
        this.sub = null;
        this.result.set(r);
        this.loading.set(false);
        onSuccess?.(r);
      },
      error: (e) => {
        this.sub = null;
        this.error.set(e instanceof ApiError ? e : new ApiError('unknown', String(e), 0));
        this.loading.set(false);
      },
    });
  }

  /**
   * User-initiated cancel: drop the subscription (which aborts the XHR) and go
   * back to a clean, retryable state. The tracker is left on `cancelled` so the
   * page can show the honest caveat — we stopped waiting, but the server may
   * well finish the job anyway and it still counts against the daily quota.
   */
  cancel(): void {
    if (!this.loading()) return;
    this.abort();
    this._tracker()?.cancel();
    this.loading.set(false);
    this.error.set(null);
    this.result.set(null);
  }

  /** Dismiss the post-cancel note without touching an error/result. */
  dismiss(): void {
    if (!this.loading()) this._tracker.set(null);
  }

  private abort(): void {
    this.sub?.unsubscribe();
    this.sub = null;
  }
}
