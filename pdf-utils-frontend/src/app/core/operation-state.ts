import { signal } from '@angular/core';
import { Observable } from 'rxjs';

import { ApiError, RunResult } from './api.models';

/**
 * Tiny reactive holder for the loading / error / result lifecycle every
 * operation page shares. Pages create one, pass its signals to
 * `<app-result-panel>`, and call `run(...)` with the ApiService observable.
 */
export class OperationState {
  readonly loading = signal(false);
  readonly error = signal<ApiError | null>(null);
  readonly result = signal<RunResult | null>(null);

  /** Return to idle: clear any in-flight loading, error and result. */
  reset(): void {
    this.loading.set(false);
    this.error.set(null);
    this.result.set(null);
  }

  /** Subscribe to an operation observable and mirror it into the signals. */
  run(obs: Observable<RunResult>, onSuccess?: (r: RunResult) => void): void {
    this.loading.set(true);
    this.error.set(null);
    this.result.set(null);
    obs.subscribe({
      next: (r) => {
        this.result.set(r);
        this.loading.set(false);
        onSuccess?.(r);
      },
      error: (e) => {
        this.error.set(e instanceof ApiError ? e : new ApiError('unknown', String(e), 0));
        this.loading.set(false);
      },
    });
  }
}
