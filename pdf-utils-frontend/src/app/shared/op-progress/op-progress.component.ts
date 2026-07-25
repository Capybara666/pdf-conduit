import {
  Component,
  EventEmitter,
  Input,
  OnChanges,
  OnDestroy,
  Output,
  computed,
  inject,
  signal,
} from '@angular/core';
import { TranslocoModule } from '@jsverse/transloco';

import { formatBytes } from '../../core/download.util';
import { NOW, RunTracker, formatDuration } from '../../core/run-progress';

/** Processing-phase copy escalates with the wait. Thresholds in ms since the request was sent. */
const STAGES: ReadonlyArray<readonly [number, string]> = [
  [90_000, 'progress.stageVeryLong'],
  [30_000, 'progress.stageLong'],
  [10_000, 'progress.stageLarger'],
  [0, 'progress.stageWorking'],
];

/** The wait becomes worth timing at this point; below it a readout is just noise. */
const ELAPSED_VISIBLE_AFTER_MS = 3_000;

/**
 * The waiting state of one operation: a determinate upload bar, then an honest
 * indeterminate "the server is working" wait, an elapsed-time readout, copy that
 * escalates with the duration, and a Cancel affordance.
 *
 * Design notes:
 * - The two waits are NEVER conflated. The bar is determinate only while bytes
 *   are going up AND the browser reported a total; the moment the upload lands
 *   it becomes indeterminate rather than parking at a finished-looking 100%.
 * - No ETA and no fabricated percentage for the server-side wait: the backend
 *   streams nothing until the result is ready, so anything else would be made up.
 *   Elapsed time is the one honest, reassuring number available.
 * - Screen readers hear phase/stage CHANGES only (`role="status"` around the
 *   message), not the per-second tick or every upload percent — the percentage
 *   lives on the progressbar's `aria-valuenow`/`aria-valuetext` instead.
 * - Motion is a single sliding indeterminate bar; `prefers-reduced-motion`
 *   swaps it for a static fill (the ticking elapsed time still proves liveness).
 */
@Component({
  selector: 'app-op-progress',
  standalone: true,
  imports: [TranslocoModule],
  template: `
    @if (waiting()) {
      <section
        class="card op-progress"
        role="group"
        aria-busy="true"
        [attr.aria-label]="'progress.label' | transloco"
      >
        <div class="row head">
          <span class="op-label">{{ label || ('common.processing' | transloco) }}</span>
          @if (showElapsed()) {
            <span class="elapsed">{{
              'progress.elapsed' | transloco: { time: elapsedText() }
            }}</span>
          }
        </div>

        @if (determinate()) {
          <div
            class="bar"
            role="progressbar"
            aria-valuemin="0"
            aria-valuemax="100"
            [attr.aria-valuenow]="percent()"
            [attr.aria-valuetext]="'progress.uploading' | transloco: { percent: percent() }"
          >
            <span class="fill" [style.width.%]="percent()"></span>
          </div>
        } @else {
          <div
            class="bar indeterminate"
            role="progressbar"
            [attr.aria-valuetext]="(messageKey() || 'common.processing') | transloco"
          >
            <span class="fill"></span>
          </div>
        }

        <div class="row detail">
          <p class="message" role="status" aria-live="polite">
            @if (messageKey(); as key) {
              {{ key | transloco }}
            }
          </p>
          @if (determinate()) {
            <span class="numbers"
              >{{ percent() }}% ·
              {{
                'progress.bytes'
                  | transloco: { done: bytes(loaded()), total: bytes(total() || 0) }
              }}</span
            >
          } @else if (uploading() && loaded() > 0) {
            <span class="numbers">{{
              'progress.sent' | transloco: { done: bytes(loaded()) }
            }}</span>
          }
        </div>

        <div class="row foot">
          <span class="files">
            @if (files().length) {
              {{ bytes(totalSize()) }}
            }
          </span>
          <button type="button" class="btn btn-ghost cancel" (click)="cancel.emit()">
            {{ 'progress.cancel' | transloco }}
          </button>
        </div>
      </section>
    }

    @if (cancelled()) {
      <section class="card op-cancelled" role="status">
        <div class="row head">
          <span class="op-label">{{ 'progress.cancelled' | transloco }}</span>
        </div>
        <p class="note">{{ 'progress.cancelledNote' | transloco }}</p>
        <div class="row foot">
          <span></span>
          <button type="button" class="btn btn-ghost" (click)="dismiss.emit()">
            {{ 'progress.dismiss' | transloco }}
          </button>
        </div>
      </section>
    }
  `,
  styles: [
    `
      .op-progress,
      .op-cancelled {
        margin-top: 1rem;
        padding: 1rem 1.25rem;
        display: flex;
        flex-direction: column;
        gap: 0.6rem;
      }
      .row {
        display: flex;
        align-items: baseline;
        justify-content: space-between;
        gap: 0.75rem;
      }
      .op-label {
        font-weight: 600;
        color: var(--text);
      }
      .elapsed,
      .numbers {
        color: var(--text-muted);
        font-size: 0.82rem;
        font-variant-numeric: tabular-nums;
        white-space: nowrap;
      }
      .message {
        margin: 0;
        color: var(--text-muted);
        font-size: 0.88rem;
        line-height: 1.45;
        min-height: 1.2em;
      }
      .note {
        margin: 0;
        color: var(--text-muted);
        font-size: 0.88rem;
        line-height: 1.5;
      }
      .files {
        color: var(--text-muted);
        font-size: 0.82rem;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .cancel {
        flex: 0 0 auto;
        color: var(--text-muted);
      }
      .cancel:hover {
        color: var(--danger);
      }

      /* ---- The bar ------------------------------------------------------ */
      .bar {
        position: relative;
        height: 6px;
        border-radius: 999px;
        background: var(--surface-2);
        border: 1px solid var(--border);
        overflow: hidden;
      }
      .bar .fill {
        display: block;
        height: 100%;
        background: var(--accent);
        border-radius: inherit;
        transition: width var(--dur-base) var(--ease-decelerate);
      }
      /* Indeterminate: a segment sweeping across a soft track. Reads as "alive"
         without claiming to know how far along the server is. */
      .bar.indeterminate {
        background: var(--accent-soft);
      }
      .bar.indeterminate .fill {
        position: absolute;
        inset-block: 0;
        width: 35%;
        transition: none;
        animation: op-sweep 1.6s var(--ease-standard) infinite;
      }
      @keyframes op-sweep {
        0% {
          transform: translateX(-110%);
        }
        100% {
          transform: translateX(320%);
        }
      }
      /* Reduced motion: no sweep at all (the global reset would otherwise freeze
         it mid-travel). A calm static fill; the elapsed readout carries liveness. */
      @media (prefers-reduced-motion: reduce) {
        .bar.indeterminate .fill {
          position: static;
          width: 100%;
          animation: none;
          background: var(--accent);
          opacity: 0.45;
        }
      }

      @media (max-width: 480px) {
        .row.detail,
        .row.foot {
          flex-direction: column;
          align-items: flex-start;
          gap: 0.35rem;
        }
        .files {
          max-width: 100%;
        }
      }
    `,
  ],
})
export class OpProgressComponent implements OnChanges, OnDestroy {
  /** Progress of the current request; `null` hides the whole component. */
  @Input() run: RunTracker | null = null;
  /** Operation-specific heading, e.g. "Compressing…". Falls back to generic copy. */
  @Input() label = '';

  /** The user asked to stop waiting. */
  @Output() cancel = new EventEmitter<void>();
  /** The user dismissed the post-cancel note. */
  @Output() dismiss = new EventEmitter<void>();

  private readonly now = inject(NOW);
  private readonly tracker = signal<RunTracker | null>(null);
  /** Bumped once a second while waiting, purely to re-evaluate elapsed time. */
  private readonly ticks = signal(0);
  private timer: ReturnType<typeof setInterval> | null = null;

  protected readonly bytes = formatBytes;

  private readonly phase = computed(() => this.tracker()?.phase() ?? null);
  protected readonly waiting = computed(() => {
    const phase = this.phase();
    return phase === 'idle' || phase === 'uploading' || phase === 'processing';
  });
  protected readonly cancelled = computed(() => this.phase() === 'cancelled');
  protected readonly uploading = computed(() => {
    const phase = this.phase();
    return phase === 'uploading' || phase === 'idle';
  });
  protected readonly loaded = computed(() => this.tracker()?.loaded() ?? 0);
  protected readonly total = computed(() => this.tracker()?.total() ?? null);

  /**
   * A real percentage is only shown while bytes are still going up. It is capped
   * at 99 so rounding can never render a full bar next to "uploading" — the jump
   * to 100 is exactly the moment the phase becomes `processing`, and then the bar
   * goes indeterminate instead.
   */
  protected readonly percent = computed(() => {
    if (!this.uploading()) return null;
    const raw = this.tracker()?.percent() ?? null;
    return raw == null ? null : Math.min(raw, 99);
  });
  protected readonly determinate = computed(() => this.percent() !== null);

  protected readonly elapsedMs = computed(() => {
    this.ticks(); // re-evaluate on every tick
    const run = this.tracker();
    return run ? run.elapsed(this.now()) : 0;
  });
  protected readonly showElapsed = computed(() => this.elapsedMs() >= ELAPSED_VISIBLE_AFTER_MS);
  protected readonly elapsedText = computed(() => formatDuration(this.elapsedMs()));

  /**
   * The one line a screen reader hears. It changes only when the PHASE or the
   * duration STAGE changes — never per tick and never per upload percent.
   */
  protected readonly messageKey = computed<string | null>(() => {
    switch (this.phase()) {
      case 'idle':
      case 'uploading':
        return 'progress.uploadingPlain';
      case 'processing':
        return this.stageKey(this.elapsedMs());
      default:
        return null;
    }
  });

  protected readonly files = computed(() => this.tracker()?.files() ?? []);
  protected readonly totalSize = computed(() =>
    this.files().reduce((sum, f) => sum + f.size, 0),
  );
  /** Full list for the tooltip — the visible summary collapses to a count. */
  protected readonly fileNames = computed(() =>
    this.files()
      .map((f) => f.name)
      .join(', '),
  );

  ngOnChanges(): void {
    this.tracker.set(this.run);
    this.ticks.set(0);
    if (this.run?.active()) this.startTimer();
    else this.stopTimer();
  }

  ngOnDestroy(): void {
    this.stopTimer();
  }

  private stageKey(elapsed: number): string {
    return STAGES.find(([threshold]) => elapsed >= threshold)![1];
  }

  private startTimer(): void {
    if (this.timer != null) return;
    this.timer = setInterval(() => {
      if (!this.tracker()?.active()) {
        this.stopTimer();
        return;
      }
      this.ticks.update((t) => t + 1);
    }, 1000);
  }

  private stopTimer(): void {
    if (this.timer != null) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }
}
