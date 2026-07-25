import { InjectionToken, Signal, computed, signal } from '@angular/core';
import { Observable } from 'rxjs';

import { RunResult } from './api.models';

/**
 * Upload / processing progress for one in-flight operation request.
 *
 * A request has two genuinely different waits and the UI must not conflate
 * them: the browser is still pushing bytes up (`uploading`, measurable), then
 * the server is working on them (`processing`, NOT measurable — the backend
 * streams nothing until the result is ready). Parking a determinate bar at
 * 100% for the second wait is the lie this model exists to prevent.
 *
 * `ApiService.runOperation` creates one tracker per call and attaches it to the
 * returned observable (see {@link RunObservable}), so pages get progress
 * without threading a callback through every typed wrapper.
 */
export type RunPhase =
  /** Created, not subscribed yet. */
  | 'idle'
  /** Bytes are going up. Determinate when the browser reports a total. */
  | 'uploading'
  /** Bytes have landed; the server is working. Never determinate. */
  | 'processing'
  /** Result received. */
  | 'done'
  /** Request failed. */
  | 'failed'
  /** The subscription was torn down before an outcome arrived. */
  | 'cancelled';

/** A phase in which the user is still waiting on this request. */
export function isActivePhase(phase: RunPhase): boolean {
  return phase === 'idle' || phase === 'uploading' || phase === 'processing';
}

/** The bits of a `File` the waiting indicator shows. */
export interface RunFile {
  readonly name: string;
  readonly size: number;
}

/**
 * Wall-clock source, injectable so specs can drive elapsed time deterministically
 * instead of sleeping. Defaults to `Date.now`.
 */
export const NOW = new InjectionToken<() => number>('now', {
  providedIn: 'root',
  factory: () => () => Date.now(),
});

/** Reactive progress state for a single request. All mutations are idempotent-safe. */
export class RunTracker {
  private readonly _phase = signal<RunPhase>('idle');
  private readonly _loaded = signal(0);
  private readonly _total = signal<number | null>(null);
  private readonly _files = signal<readonly RunFile[]>([]);
  private readonly _startedAt = signal<number | null>(null);

  readonly phase: Signal<RunPhase> = this._phase.asReadonly();
  /** Bytes pushed so far in the upload phase. */
  readonly loaded: Signal<number> = this._loaded.asReadonly();
  /** Total upload bytes, or `null` when the browser cannot compute it. */
  readonly total: Signal<number | null> = this._total.asReadonly();
  /** The files being sent (for "3 files · 25 MB" reassurance). */
  readonly files: Signal<readonly RunFile[]> = this._files.asReadonly();
  /** Clock reading when the request was sent, or `null` before that. */
  readonly startedAt: Signal<number | null> = this._startedAt.asReadonly();

  /**
   * Upload completion 0–100, or `null` when it cannot be known. `null` means
   * "show an indeterminate bar" — never a fabricated number.
   */
  readonly percent: Signal<number | null> = computed(() => {
    const total = this._total();
    if (total == null || total <= 0) return null;
    return Math.max(0, Math.min(100, Math.round((this._loaded() / total) * 100)));
  });

  /** True while the user is still waiting on this request. */
  readonly active: Signal<boolean> = computed(() => isActivePhase(this._phase()));

  constructor(private readonly now: () => number = () => Date.now()) {}

  /** The request left the browser: start the clock and the upload phase. */
  begin(files: readonly RunFile[] = []): void {
    if (!this.active()) return;
    this._files.set(files);
    if (this._startedAt() == null) this._startedAt.set(this.now());
    this._phase.set('uploading');
  }

  /**
   * An `HttpEventType.UploadProgress` tick. A `total` of `undefined` keeps the
   * bar indeterminate. Reaching the total flips straight to `processing` so the
   * bar never sits at a finished-looking 100% while the server works.
   */
  upload(loaded: number, total?: number): void {
    if (!this.active()) return;
    if (this._startedAt() == null) this._startedAt.set(this.now());
    this._loaded.set(loaded);
    if (total != null && Number.isFinite(total) && total > 0) {
      this._total.set(total);
      if (loaded >= total) {
        this._phase.set('processing');
        return;
      }
    }
    this._phase.set('uploading');
  }

  /** Bytes are up (or the response started arriving): the server owns the wait now. */
  processing(): void {
    if (!this.active()) return;
    if (this._startedAt() == null) this._startedAt.set(this.now());
    this._phase.set('processing');
  }

  /** Terminal: a result arrived. */
  succeed(): void {
    if (this.active()) this._phase.set('done');
  }

  /** Terminal: the request failed. */
  fail(): void {
    if (this.active()) this._phase.set('failed');
  }

  /**
   * Terminal: torn down without an outcome (the user cancelled, or the page
   * was destroyed). Called from the request's `finalize`, so it is a no-op once
   * `succeed()` / `fail()` has run.
   */
  cancel(): void {
    if (this.active()) this._phase.set('cancelled');
  }

  /** Milliseconds waited so far, or 0 before the request was sent. */
  elapsed(at: number): number {
    const started = this._startedAt();
    return started == null ? 0 : Math.max(0, at - started);
  }
}

/** An operation observable carrying the progress of the request it will issue. */
export type RunObservable = Observable<RunResult> & { readonly run: RunTracker };

/** Attach a tracker to an operation observable (non-enumerable, so it never leaks into JSON). */
export function withRunTracker(source: Observable<RunResult>, run: RunTracker): RunObservable {
  return Object.defineProperty(source, 'run', {
    value: run,
    enumerable: false,
    writable: false,
  }) as RunObservable;
}

/** The tracker attached to an operation observable, or `null` for a plain one. */
export function runTrackerOf(source: Observable<RunResult>): RunTracker | null {
  const run = (source as Partial<RunObservable>).run;
  return run instanceof RunTracker ? run : null;
}

/** The `File` parts of a multipart body, for the indicator's file summary. */
export function filesOf(body: FormData): RunFile[] {
  const files: RunFile[] = [];
  body.forEach((value) => {
    if (typeof value !== 'string' && typeof (value as File).size === 'number') {
      const file = value as File;
      files.push({ name: file.name, size: file.size });
    }
  });
  return files;
}

/** `m:ss` (or `h:mm:ss` past an hour) for the elapsed-time readout. */
export function formatDuration(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  const seconds = total % 60;
  const minutes = Math.floor(total / 60) % 60;
  const hours = Math.floor(total / 3600);
  const pad = (n: number) => String(n).padStart(2, '0');
  return hours > 0 ? `${hours}:${pad(minutes)}:${pad(seconds)}` : `${minutes}:${pad(seconds)}`;
}
