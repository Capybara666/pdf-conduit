import { Component, OnDestroy, computed, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';

import { ApiError, RunResult } from '../../core/api.models';
import { ApiService } from '../../core/api.service';
import { downloadRunResult, formatBytes } from '../../core/download.util';
import { loadPdf } from '../../core/pdfjs';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
import { PageGridComponent } from '../../shared/page-grid/page-grid.component';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
import { ResultPanelComponent } from '../../shared/result-panel/result-panel.component';
import { SpinnerComponent } from '../../shared/spinner/spinner.component';
import {
  TargetSizeComponent,
  TargetUnit,
  UNIT_BYTES,
  composeTargetSize,
} from '../../shared/target-size/target-size.component';

type PageSize = 'FIT' | 'A4' | 'A3' | 'LETTER';

interface WizardFile {
  file: File;
  /** Optional page range (PDF inputs only). */
  pages: string;
}

/** i18n keys for the five wizard steps (select → arrange → … → export). */
const STEP_KEYS = [
  'pages.wizard.step1',
  'pages.wizard.step2',
  'pages.wizard.step3',
  'pages.wizard.step4',
  'pages.wizard.step5',
];

/** Internal index of the page-settings step, which is only in the flow for non-PDF inputs. */
const PAGE_SETTINGS_STEP = 2;

/** Inputs the wizard accepts, offered by the drop zone on the select step. */
const ACCEPT = '.pdf,image/*,.docx,.odt,.rtf,.txt,.xlsx,.pptx';

/**
 * Guided build-and-export flow mirroring the desktop wizard. It composes the
 * existing REST operations: images/office inputs are converted via `to-pdf`
 * (honouring the chosen page size), PDF inputs with a page range are trimmed via
 * `extract`, all parts are combined with `merge`, and an optional target size
 * runs the result through `compress`.
 */
@Component({
  selector: 'app-wizard-page',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    TranslocoModule,
    FileDropZoneComponent,
    PageGridComponent,
    PageHeaderComponent,
    ResultPanelComponent,
    SpinnerComponent,
    TargetSizeComponent,
  ],
  template: `
    <section class="op-page">
      <app-page-header
        [title]="'pages.wizard.title' | transloco"
        [description]="'pages.wizard.description' | transloco"
      />

      <!-- Only the steps currently in the flow are listed, and each dot is numbered
           by its POSITION in that list so the sequence always reads 1..n. -->
      <ol class="stepper">
        @for (i of visibleSteps(); track i; let pos = $index) {
          <li [class.active]="i === step()" [class.done]="i < step()">
            <button
              type="button"
              class="step-btn"
              [disabled]="!canGoTo(i)"
              [attr.aria-current]="i === step() ? 'step' : null"
              (click)="goTo(i)"
            >
              <span class="dot">{{ i < step() ? '✓' : pos + 1 }}</span>
              <span class="lbl">{{ steps[i] | transloco }}</span>
            </button>
          </li>
        }
      </ol>

      <div class="card">
        <!-- Step 1: select -->
        @if (step() === 0) {
          <h2 class="step-h">{{ 'pages.wizard.selectTitle' | transloco }}</h2>
          <p class="step-desc">{{ 'pages.wizard.selectDesc' | transloco }}</p>
          <!-- The zone's own selection is fed back from the wizard's list, so it
               stays in step with removals and with files added later. -->
          <app-file-drop-zone
            [multiple]="true"
            [accept]="accept"
            [files]="selectedFiles()"
            [hint]="'pages.wizard.selectHint' | transloco"
            (filesChange)="onFiles($event)"
          />
        }

        <!-- Step 2: arrange + page ranges -->
        @if (step() === 1) {
          <h2 class="step-h">{{ 'pages.wizard.arrangeTitle' | transloco }}</h2>
          <p class="step-desc">{{ 'pages.wizard.arrangeDesc' | transloco }}</p>
          <ul
            class="file-list"
            role="list"
            (dragover)="onContainerDragOver($event)"
            (drop)="onDrop($event)"
          >
            @for (it of items(); track it.file; let i = $index) {
              @if (dragIndex() !== null && dropIndex() === i) {
                <li class="drop-marker" aria-hidden="true"></li>
              }
              <li
                class="file-card"
                [class.dragging]="dragIndex() === i"
                [class.expanded]="expandedFile() === it.file"
                (dragover)="onDragOver($event, i)"
                (drop)="onDrop($event)"
              >
                <div
                  class="file-head"
                  draggable="true"
                  (dragstart)="onDragStart($event, i)"
                  (dragend)="dragEnd()"
                >
                  <span class="grip" aria-hidden="true">⠿</span>
                  <span class="idx" aria-hidden="true">{{ i + 1 }}</span>

                  <span class="thumb-box" aria-hidden="true">
                    @if (thumbUrl(it.file); as url) {
                      <img class="thumb-img" [src]="url" alt="" />
                    } @else if (fileKind(it.file) === 'pdf' || fileKind(it.file) === 'image') {
                      <span class="thumb-ph"><app-spinner /></span>
                    } @else {
                      <span class="thumb-icon">{{ docIcon(it.file) }}</span>
                    }
                  </span>

                  <span class="file-meta">
                    <span class="name" [title]="it.file.name">{{ it.file.name }}</span>
                    <span class="sub">
                      @if (fileKind(it.file) === 'pdf') {
                        @if (pageCount(it.file); as pc) {
                          {{ 'pageGrid.count' | transloco: { n: pc } }}
                        }
                        @if (it.pages.trim()) {
                          <span class="chip">{{ 'pages.wizard.rangeSummary' | transloco: { range: it.pages.trim() } }}</span>
                        } @else {
                          <span class="chip subtle">{{ 'pages.wizard.allPages' | transloco }}</span>
                        }
                      } @else {
                        {{ (fileKind(it.file) === 'image' ? 'pages.wizard.typeImage' : 'pages.wizard.typeDocument') | transloco }}
                      }
                    </span>
                  </span>

                  @if (fileKind(it.file) === 'pdf') {
                    <button
                      type="button"
                      class="btn btn-ghost choose-btn"
                      [class.on]="expandedFile() === it.file"
                      (click)="toggleExpand(it.file)"
                      [attr.aria-expanded]="expandedFile() === it.file"
                    >
                      {{ (expandedFile() === it.file ? 'common.done' : 'pages.wizard.choosePages') | transloco }}
                    </button>
                  }
                  <button
                    type="button"
                    class="icon-btn"
                    (click)="remove(i)"
                    [attr.aria-label]="'common.remove' | transloco"
                  >✕</button>
                </div>

                @if (fileKind(it.file) === 'pdf' && expandedFile() === it.file) {
                  <div class="page-picker">
                    <app-page-grid
                      mode="select"
                      [thumbWidth]="96"
                      [file]="it.file"
                      [range]="it.pages"
                      (rangeChange)="setPagesFor(it.file, $event)"
                    />
                    <label class="range-fallback">
                      <span class="fallback-lbl">{{ 'pages.wizard.rangeManual' | transloco }}</span>
                      <input
                        class="range-in"
                        type="text"
                        [value]="it.pages"
                        (input)="setPagesFor(it.file, $any($event.target).value)"
                        [placeholder]="'pages.wizard.rangePlaceholder' | transloco"
                      />
                    </label>
                  </div>
                }
              </li>
            }
            @if (dragIndex() !== null && dropIndex() === items().length) {
              <li class="drop-marker" aria-hidden="true"></li>
            }
          </ul>
        }

        <!-- Step 3: page settings -->
        @if (step() === 2) {
          <h2 class="step-h">{{ 'pages.wizard.pageSettingsTitle' | transloco }}</h2>
          <p class="step-desc">{{ 'pages.wizard.pageSettingsDesc' | transloco }}</p>
          <div class="form-grid">
            <div class="field">
              <label for="wz-size">{{ 'pages.wizard.imagePageSize' | transloco }}</label>
              <select id="wz-size" [value]="pageSize()" (change)="pageSize.set($any($event.target).value)">
                <option value="FIT">{{ 'pages.wizard.sizeFit' | transloco }}</option>
                <option value="A4">A4</option>
                <option value="A3">A3</option>
                <option value="LETTER">{{ 'pages.wizard.sizeLetter' | transloco }}</option>
              </select>
              <span class="help">{{ 'pages.wizard.imagePageSizeHelp' | transloco }}</span>
            </div>
          </div>
        }

        <!-- Step 4: compression -->
        @if (step() === 3) {
          <h2 class="step-h">{{ 'pages.wizard.compressionTitle' | transloco }}</h2>
          <p class="step-desc">{{ 'pages.wizard.compressionDesc' | transloco }}</p>
          <label class="check" style="margin-bottom:0.75rem">
            <input type="checkbox" [checked]="compress()" (change)="compress.set($any($event.target).checked)" />
            {{ 'pages.wizard.compressToggle' | transloco }}
          </label>
          @if (compress()) {
            <div class="form-grid">
              <div class="field">
                <label for="wz-target">{{ 'pages.wizard.target' | transloco }}</label>
                <app-target-size [amount]="targetAmount" [unit]="targetUnit" inputId="wz-target" />
                @if (targetAmount.invalid) {
                  <span class="err">{{ 'pages.wizard.targetError' | transloco }}</span>
                } @else if (targetIsNoop()) {
                  <span class="err">{{ 'pages.wizard.targetNoop' | transloco }}</span>
                }
              </div>
            </div>
          }
        }

        <!-- Step 5: export -->
        @if (step() === 4) {
          <h2 class="step-h">{{ 'pages.wizard.reviewTitle' | transloco }}</h2>
          <p class="step-desc">{{ 'pages.wizard.reviewDesc' | transloco }}</p>
          <ul class="summary">
            <li><span>{{ 'pages.wizard.sumFiles' | transloco }}</span><b>{{ items().length }}</b></li>
            <li>
              <span>{{ 'pages.wizard.sumOrder' | transloco }}</span>
              <ol class="order-list">
                @for (it of items(); track it.file) {
                  <li [title]="it.file.name">{{ it.file.name }}</li>
                }
              </ol>
            </li>
            @if (needsPageSettings()) {
              <li>
                <span>{{ 'pages.wizard.sumImageSize' | transloco }}</span>
                <b>
                  @switch (pageSize()) {
                    @case ('FIT') { {{ 'pages.wizard.sizeFit' | transloco }} }
                    @case ('LETTER') { {{ 'pages.wizard.sizeLetter' | transloco }} }
                    @default { {{ pageSize() }} }
                  }
                </b>
              </li>
            }
            <li>
              <span>{{ 'pages.wizard.sumCompress' | transloco }}</span>
              <b>
                @if (!compress()) {
                  {{ 'pages.wizard.compressNo' | transloco }}
                } @else if (targetAmount.valid) {
                  {{ composedTarget() }}
                } @else {
                  {{ 'pages.wizard.targetError' | transloco }}
                }
              </b>
            </li>
          </ul>

          @if (busy() || error() || result()) {
            <app-result-panel
              [loading]="busy()"
              [loadingLabel]="progress()"
              [error]="error()"
              [result]="result()"
              (retry)="runExport()"
            />
          }
        }
      </div>

      <div class="btn-row">
        <button type="button" class="btn" [disabled]="step() === 0 || busy()" (click)="back()">{{ 'common.back' | transloco }}</button>
        @if (step() < steps.length - 1) {
          <button type="button" class="btn btn-primary" [disabled]="!canNext()" (click)="next()">{{ 'common.next' | transloco }}</button>
        } @else {
          <button
            type="button"
            class="btn btn-primary"
            [disabled]="!items().length || busy() || (compress() && targetAmount.invalid)"
            (click)="runExport()"
          >
            {{ (result() ? 'common.reExport' : 'common.export') | transloco }}
          </button>
        }
        <button type="button" class="btn btn-ghost" [disabled]="busy()" (click)="restart()">{{ 'common.startOver' | transloco }}</button>
      </div>
    </section>
  `,
  styles: [
    `
      .stepper {
        display: flex;
        flex-wrap: wrap;
        gap: 0.5rem 1rem;
        list-style: none;
        margin: 0;
        padding: 0;
      }
      .stepper li {
        display: flex;
        align-items: center;
        color: var(--text-muted);
        font-size: 0.85rem;
      }
      .stepper li.active {
        color: var(--text);
        font-weight: 600;
      }
      /* The step is a real button (jump back to any reached step); it inherits the
         li's colour/weight so the dot + label keep their step-state styling. */
      .step-btn {
        display: inline-flex;
        align-items: center;
        gap: 0.45rem;
        margin: 0;
        padding: 0;
        border: 0;
        background: none;
        font: inherit;
        color: inherit;
        text-align: left;
        cursor: pointer;
        border-radius: 6px;
      }
      .step-btn:disabled {
        cursor: default;
      }
      /* The dots read as decoration, so a reachable step has to advertise itself
         on hover — the pointer cursor alone is easy to miss. */
      .step-btn:not(:disabled):hover .lbl {
        text-decoration: underline;
      }
      .step-btn:focus-visible {
        outline: 2px solid var(--accent);
        outline-offset: 3px;
      }
      .dot {
        width: 1.5rem;
        height: 1.5rem;
        border-radius: 50%;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        background: var(--surface-2);
        border: 1px solid var(--border-strong);
        font-size: 0.8rem;
      }
      .stepper li.active .dot {
        background: var(--accent);
        color: var(--accent-contrast);
        border-color: var(--accent);
      }
      .stepper li.done .dot {
        background: var(--success);
        color: #fff;
        border-color: var(--success);
      }
      .step-h {
        margin: 0 0 0.35rem;
        font-size: 1.1rem;
      }
      .step-desc {
        color: var(--text-muted);
        font-size: 0.85rem;
        margin: 0 0 1rem;
      }
      .range-in {
        width: 140px;
      }

      /* ---- Step 2: file cards ---- */
      .file-list {
        list-style: none;
        margin: 0;
        padding: 0;
        display: flex;
        flex-direction: column;
        gap: 0.6rem;
      }
      .file-card {
        border: 1px solid var(--border);
        border-radius: var(--radius);
        background: var(--surface);
        overflow: hidden;
        transition: border-color 0.12s ease, box-shadow 0.12s ease, opacity 0.12s ease;
      }
      .file-card.expanded {
        border-color: var(--accent);
        box-shadow: 0 0 0 1px var(--accent-soft);
      }
      .file-card.dragging {
        opacity: 0.5;
      }
      /* Slim insertion marker: a horizontal bar in the gap where the row lands. */
      .drop-marker {
        height: 3px;
        margin: 0;
        padding: 0;
        list-style: none;
        border-radius: 2px;
        background: var(--accent);
        box-shadow: 0 0 0 1px var(--accent-soft);
        /* Let the cursor pass through the thin marker to the row/container
           beneath so releasing on it is still a valid drop (not a no-op). */
        pointer-events: none;
      }
      .file-head {
        display: flex;
        align-items: center;
        gap: 0.7rem;
        padding: 0.6rem 0.75rem;
      }
      .grip {
        cursor: grab;
        color: var(--text-muted);
        font-size: 1.1rem;
        line-height: 1;
        user-select: none;
        touch-action: none;
      }
      .file-head:active .grip {
        cursor: grabbing;
      }
      .idx {
        flex: 0 0 auto;
        width: 1.5rem;
        height: 1.5rem;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        border-radius: 50%;
        background: var(--surface-2);
        border: 1px solid var(--border-strong);
        font-size: 0.78rem;
        font-variant-numeric: tabular-nums;
      }
      .thumb-box {
        flex: 0 0 auto;
        width: 42px;
        height: 54px;
        border-radius: 6px;
        border: 1px solid var(--border);
        background: #fff;
        overflow: hidden;
        display: flex;
        align-items: center;
        justify-content: center;
      }
      .thumb-img {
        max-width: 100%;
        max-height: 100%;
        display: block;
      }
      .thumb-ph {
        display: inline-flex;
      }
      .thumb-icon {
        font-size: 1.5rem;
        line-height: 1;
      }
      .file-meta {
        flex: 1 1 auto;
        min-width: 0;
        display: flex;
        flex-direction: column;
        gap: 0.15rem;
      }
      .file-meta .name {
        font-weight: 600;
        font-size: 0.9rem;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
      }
      .file-meta .sub {
        display: flex;
        align-items: center;
        flex-wrap: wrap;
        gap: 0.4rem;
        font-size: 0.78rem;
        color: var(--text-muted);
      }
      .chip {
        display: inline-flex;
        align-items: center;
        padding: 0.05rem 0.45rem;
        border-radius: 999px;
        background: var(--accent-soft);
        color: var(--accent);
        font-variant-numeric: tabular-nums;
        font-weight: 600;
      }
      .chip.subtle {
        background: var(--surface-2);
        color: var(--text-muted);
        font-weight: 500;
      }
      .choose-btn {
        flex: 0 0 auto;
        white-space: nowrap;
        font-size: 0.82rem;
        padding: 0.35rem 0.7rem;
      }
      .choose-btn.on {
        border-color: var(--accent);
        color: var(--accent);
      }
      .page-picker {
        border-top: 1px solid var(--border);
        background: var(--surface-2);
        padding: 0.85rem 0.9rem 0.9rem;
      }
      .range-fallback {
        display: flex;
        align-items: center;
        gap: 0.5rem;
        margin-top: 0.75rem;
        flex-wrap: wrap;
      }
      .fallback-lbl {
        font-size: 0.8rem;
        color: var(--text-muted);
      }
      @media (max-width: 480px) {
        .file-meta .name {
          max-width: 40vw;
        }
        .choose-btn {
          padding: 0.35rem 0.5rem;
        }
      }
      .summary {
        list-style: none;
        margin: 0 0 1rem;
        padding: 0;
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
        gap: 0.5rem;
      }
      /* Grid children default to min-width:auto, which lets a long value push the
         tile (and the card) wider than the column — pin it to 0 so values clip
         or wrap inside the tile instead. */
      .summary > li {
        display: flex;
        flex-direction: column;
        min-width: 0;
        padding: 0.5rem 0.75rem;
        background: var(--surface-2);
        border-radius: 8px;
      }
      .summary > li > span {
        font-size: 0.72rem;
        text-transform: uppercase;
        color: var(--text-muted);
      }
      .summary > li > b {
        overflow-wrap: anywhere;
      }
      /* One file per line, each ellipsized (full name in the tooltip); the index
         is a counter so the row's overflow:hidden cannot clip a list marker. */
      .order-list {
        list-style: none;
        counter-reset: order;
        margin: 0.1rem 0 0;
        padding: 0;
        font-weight: 600;
      }
      .order-list li {
        counter-increment: order;
        min-width: 0;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
      }
      .order-list li::before {
        content: counter(order) '. ';
        color: var(--text-muted);
        font-weight: 500;
      }
    `,
  ],
})
export class WizardPage implements OnDestroy {
  private readonly transloco = inject(TranslocoService);
  protected readonly steps = STEP_KEYS;
  protected readonly accept = ACCEPT;
  protected readonly formatBytes = formatBytes;

  protected readonly step = signal(0);
  /** Furthest step the user has advanced to; the stepper can jump anywhere up to it. */
  protected readonly reached = signal(0);
  protected readonly items = signal<WizardFile[]>([]);
  /** The plain File list behind `items`, fed back into the drop zone's own selection.
   *  Being a computed, it keeps one array reference per `items` change, so the drop
   *  zone's input only fires when the selection actually changes. */
  protected readonly selectedFiles = computed(() => this.items().map((it) => it.file));
  protected readonly dragIndex = signal<number | null>(null);
  /** Insertion index (0..items().length) where a drop would land, or null. */
  protected readonly dropIndex = signal<number | null>(null);
  /** Which PDF file (identity) has its visual page-picker expanded (only one at a time). */
  protected readonly expandedFile = signal<File | null>(null);

  // First-page thumbnail data-URLs / object-URLs keyed by File identity, plus the
  // PDF page counts discovered while rendering. `thumbTick` re-triggers template
  // reads once an async thumbnail lands.
  private readonly thumbs = new Map<File, string>();
  private readonly pageCounts = new Map<File, number>();
  private readonly objectUrls: string[] = [];
  private readonly thumbTick = signal(0);
  protected readonly pageSize = signal<PageSize>('FIT');
  protected readonly compress = signal(false);
  // The user types a positive NUMBER and picks a unit; together they compose the
  // "5MB"-style string the backend's `targetSize` parser expects.
  protected readonly targetAmount = new FormControl<number | null>(5, {
    validators: [Validators.required, Validators.min(0.0000001)],
  });
  protected readonly targetUnit = new FormControl<TargetUnit>('MB', { nonNullable: true });

  protected readonly busy = signal(false);
  protected readonly progress = signal('');
  protected readonly error = signal<ApiError | null>(null);
  protected readonly result = signal<RunResult | null>(null);

  /** The backend `targetSize` string for the current amount + unit (e.g. "5MB"). */
  protected composedTarget(): string {
    return composeTargetSize(this.targetAmount.value, this.targetUnit.value);
  }

  /**
   * True when the target is at or above the summed size of the selected inputs,
   * so compression has nothing left to shrink.
   *
   * That sum only APPROXIMATES the merged output — merging shares resources,
   * page ranges drop pages, and images/office inputs are re-rendered — so this
   * is a warning, never a block on exporting.
   */
  protected targetIsNoop(): boolean {
    const amount = this.targetAmount.value;
    if (this.targetAmount.invalid || amount == null || !(amount > 0)) return false;
    const total = this.items().reduce((sum, it) => sum + it.file.size, 0);
    return total > 0 && amount * UNIT_BYTES[this.targetUnit.value] >= total;
  }

  constructor(private readonly api: ApiService) {}

  ngOnDestroy(): void {
    this.revokeUrls();
  }

  isPdf(f: File): boolean {
    return f.type === 'application/pdf' || f.name.toLowerCase().endsWith('.pdf');
  }
  isImage(f: File): boolean {
    return f.type.startsWith('image/') || /\.(png|jpe?g|gif|bmp|webp|tiff?)$/i.test(f.name);
  }

  /** Coarse input classification driving the thumbnail + summary rendering. */
  fileKind(f: File): 'pdf' | 'image' | 'doc' {
    if (this.isPdf(f)) return 'pdf';
    if (this.isImage(f)) return 'image';
    return 'doc';
  }

  /** Emoji icon for office / text inputs that have no visual thumbnail. */
  docIcon(f: File): string {
    const n = f.name.toLowerCase();
    if (/\.(txt|md)$/.test(n)) return '📄';
    if (/\.(xlsx?|ods|csv)$/.test(n)) return '📊';
    if (/\.(pptx?|odp)$/.test(n)) return '📽️';
    return '📝';
  }

  thumbUrl(f: File): string | null {
    this.thumbTick(); // subscribe to async thumbnail arrivals
    return this.thumbs.get(f) ?? null;
  }

  pageCount(f: File): number | null {
    this.thumbTick();
    return this.pageCounts.get(f) ?? null;
  }

  onFiles(files: File[]): void {
    this.mergeFiles(files);
  }

  /**
   * Reconcile the selection with an incoming list, matched by `File` identity:
   * a file already selected keeps its item (and therefore its page range), new
   * files are appended in the incoming order, and files no longer listed are
   * dropped along with their cached thumbnail. The drop zone accumulates and
   * re-emits its whole selection, so adding one file arrives here as the full
   * list — rebuilding items from it would throw away every chosen range.
   */
  private mergeFiles(files: File[]): void {
    const existing = new Map(this.items().map((it) => [it.file, it] as const));
    this.items.set(files.map((file) => existing.get(file) ?? { file, pages: '' }));

    const kept = new Set(files);
    const expanded = this.expandedFile();
    if (expanded && !kept.has(expanded)) this.expandedFile.set(null);
    for (const file of existing.keys()) {
      if (!kept.has(file)) this.forget(file);
    }
    // Thumbnails are keyed by File, so only files without one are rendered.
    for (const file of files) {
      if (!this.thumbs.has(file)) void this.buildThumb(file);
    }
    this.ensureStepVisible();
  }

  /** Drop a removed file's cached thumbnail/page count, revoking its object URL
   *  (image thumbnails only — PDF previews are data-URLs). */
  private forget(file: File): void {
    const url = this.thumbs.get(file);
    this.thumbs.delete(file);
    this.pageCounts.delete(file);
    if (!url) return;
    const at = this.objectUrls.indexOf(url);
    if (at >= 0) {
      URL.revokeObjectURL(url);
      this.objectUrls.splice(at, 1);
    }
  }

  /** Write a page range back by file identity (robust across drag-reorder). */
  setPagesFor(file: File, value: string): void {
    const next = this.items().slice();
    const idx = next.findIndex((it) => it.file === file);
    if (idx < 0) return;
    next[idx] = { ...next[idx], pages: value };
    this.items.set(next);
  }

  toggleExpand(file: File): void {
    this.expandedFile.set(this.expandedFile() === file ? null : file);
  }

  remove(i: number): void {
    const next = this.items().slice();
    const [gone] = next.splice(i, 1);
    if (gone && this.expandedFile() === gone.file) this.expandedFile.set(null);
    this.items.set(next);
    if (gone) this.forget(gone.file);
    this.ensureStepVisible();
  }

  /** Render a small first-page thumbnail (PDF) or object-URL (image), cached by File. */
  private async buildThumb(file: File): Promise<void> {
    const kind = this.fileKind(file);
    if (kind === 'image') {
      const url = URL.createObjectURL(file);
      this.objectUrls.push(url);
      this.thumbs.set(file, url);
      this.thumbTick.update((v) => v + 1);
      return;
    }
    if (kind !== 'pdf') return;
    try {
      const buf = await file.arrayBuffer();
      const doc = await loadPdf(buf);
      this.pageCounts.set(file, doc.numPages);
      const page = await doc.getPage(1);
      const dpr = Math.min(window.devicePixelRatio || 1, 2);
      const base = page.getViewport({ scale: 1 });
      const scale = (84 * dpr) / base.width;
      const vp = page.getViewport({ scale });
      const canvas = document.createElement('canvas');
      canvas.width = Math.round(vp.width);
      canvas.height = Math.round(vp.height);
      const ctx = canvas.getContext('2d');
      if (ctx) {
        await page.render({ canvasContext: ctx, viewport: vp }).promise;
        this.thumbs.set(file, canvas.toDataURL('image/png'));
      }
      void doc.destroy();
    } catch {
      // Leave the thumbnail unset — the row simply shows no preview.
    } finally {
      // A file removed while its preview was rendering must not stay cached.
      if (!this.selectedFiles().includes(file)) this.forget(file);
      this.thumbTick.update((v) => v + 1);
    }
  }

  private revokeUrls(): void {
    for (const url of this.objectUrls) URL.revokeObjectURL(url);
    this.objectUrls.length = 0;
  }

  /**
   * Update the insertion marker (not the list) while dragging: the cursor's
   * top half of a row inserts BEFORE it, bottom half AFTER it. The list only
   * reorders on drop, so the marker unambiguously previews the outcome.
   */
  onDragStart(ev: DragEvent, i: number): void {
    this.dragIndex.set(i);
    // Populate dataTransfer so Firefox reliably initiates the drag, and flag it
    // as a move (reorder), not a copy.
    if (ev.dataTransfer) {
      ev.dataTransfer.effectAllowed = 'move';
      ev.dataTransfer.setData('text/plain', String(i));
    }
  }

  /**
   * Container-level dragover: preventDefault so a release ANYWHERE inside the
   * list (a gap, the thin insertion marker) is a valid drop and the following
   * `drop` fires. It never moves the marker — the per-row dragover owns that;
   * this only keeps the drop alive when the pointer isn't over a row.
   */
  onContainerDragOver(ev: DragEvent): void {
    if (this.dragIndex() === null) return;
    ev.preventDefault();
  }

  onDragOver(ev: DragEvent, over: number): void {
    ev.preventDefault();
    if (this.dragIndex() === null) return;
    const el = ev.currentTarget as HTMLElement;
    const rect = el.getBoundingClientRect();
    const leading = ev.clientY < rect.top + rect.height / 2;
    this.dropIndex.set(leading ? over : over + 1);
  }

  onDrop(ev: DragEvent): void {
    ev.preventDefault();
    const from = this.dragIndex();
    const to = this.dropIndex();
    if (from !== null && to !== null) {
      const next = this.items().slice();
      const [moved] = next.splice(from, 1);
      // Same-list off-by-one: after removing `from`, a marker past it shifts down.
      const target = to > from ? to - 1 : to;
      next.splice(target, 0, moved);
      this.items.set(next);
    }
    this.dragEnd();
  }

  dragEnd(): void {
    this.dragIndex.set(null);
    this.dropIndex.set(null);
  }

  /**
   * True when the selection holds anything that has to be turned into PDF pages
   * (image or office input) — the only case where the page-settings step's page
   * size reaches the result. An all-PDF selection skips that step entirely.
   */
  needsPageSettings(): boolean {
    return this.items().some((it) => this.fileKind(it.file) !== 'pdf');
  }

  /** The internal step indices currently in the flow, in order. */
  visibleSteps(): number[] {
    const all = [...this.steps.keys()];
    return this.needsPageSettings() ? all : all.filter((i) => i !== PAGE_SETTINGS_STEP);
  }

  /** Nearest visible step after / before `from`; both accept a `from` that is
   *  itself hidden, so they also rescue a step that just left the flow. */
  private nextVisible(from: number): number {
    const visible = this.visibleSteps();
    return visible.find((i) => i > from) ?? visible[visible.length - 1];
  }
  private prevVisible(from: number): number {
    const before = this.visibleSteps().filter((i) => i < from);
    return before.length ? before[before.length - 1] : this.visibleSteps()[0];
  }

  /** Move off a step that is no longer part of the flow (the selection can turn
   *  all-PDF while page settings is on screen), so nobody is left stranded. */
  private ensureStepVisible(): void {
    if (this.visibleSteps().includes(this.step())) return;
    this.step.set(this.nextVisible(this.step()));
  }

  canNext(): boolean {
    if (this.busy()) return false;
    if (this.step() === 0) return this.items().length > 0;
    return true;
  }

  /** A stepper step is reachable when it is in the flow, already unlocked, and
   *  isn't the current one. */
  canGoTo(i: number): boolean {
    return (
      !this.busy() && i <= this.reached() && i !== this.step() && this.visibleSteps().includes(i)
    );
  }

  goTo(i: number): void {
    if (this.canGoTo(i)) this.step.set(i);
  }

  next(): void {
    if (!this.canNext()) return;
    const target = this.nextVisible(this.step());
    this.step.set(target);
    this.reached.update((r) => Math.max(r, target));
  }
  back(): void {
    this.step.set(this.prevVisible(this.step()));
  }
  restart(): void {
    this.revokeUrls();
    this.thumbs.clear();
    this.pageCounts.clear();
    this.expandedFile.set(null);
    this.items.set([]);
    this.step.set(0);
    this.reached.set(0);
    this.result.set(null);
    this.error.set(null);
    // "Start over" also drops the previous run's export settings, back to the
    // values a first-time visitor sees.
    this.pageSize.set('FIT');
    this.compress.set(false);
    this.targetAmount.reset(5);
    this.targetUnit.reset('MB');
  }

  download(): void {
    if (this.result()) downloadRunResult(this.result()!);
  }

  /** Convert → merge → optional compress, driven sequentially. */
  async runExport(): Promise<void> {
    const items = this.items();
    if (!items.length) return;
    this.busy.set(true);
    this.error.set(null);
    this.result.set(null);
    try {
      const parts: File[] = [];
      for (let i = 0; i < items.length; i++) {
        const it = items[i];
        this.progress.set(
          this.transloco.translate('pages.wizard.preparing', {
            current: i + 1,
            total: items.length,
            name: it.file.name,
          }),
        );
        parts.push(await this.toPdfPart(it));
      }

      this.progress.set(this.transloco.translate('pages.wizard.merging'));
      let out = await firstValueFrom(this.api.merge(this.formData('files', parts)));

      const amount = this.targetAmount.value;
      if (this.compress() && this.targetAmount.valid && amount != null && amount > 0) {
        this.progress.set(this.transloco.translate('pages.wizard.compressing'));
        const fd = new FormData();
        fd.append('files', this.asFile(out), out.filename);
        fd.append('targetSize', this.composedTarget());
        out = await firstValueFrom(this.api.compress(fd));
      }

      this.result.set(out);
    } catch (e) {
      this.error.set(e instanceof ApiError ? e : new ApiError('unknown', String(e), 0));
    } finally {
      this.busy.set(false);
      this.progress.set('');
    }
  }

  /** Produce a PDF File for one input: convert images/office, trim PDFs by range. */
  private async toPdfPart(it: WizardFile): Promise<File> {
    if (this.isPdf(it.file)) {
      if (it.pages.trim()) {
        const fd = new FormData();
        fd.append('file', it.file, it.file.name);
        fd.append('pages', it.pages.trim());
        fd.append('separate', 'false');
        const r = await firstValueFrom(this.api.extract(fd));
        return this.asFile(r);
      }
      return it.file;
    }
    // Image or office document → convert to PDF at the chosen page size.
    const fd = new FormData();
    fd.append('files', it.file, it.file.name);
    fd.append('pageSize', this.pageSize());
    const r = await firstValueFrom(this.api.toPdf(fd));
    return this.asFile(r);
  }

  private formData(field: string, files: File[]): FormData {
    const fd = new FormData();
    for (const f of files) fd.append(field, f, f.name);
    return fd;
  }

  private asFile(r: RunResult): File {
    return new File([r.blob], r.filename, { type: r.blob.type || 'application/pdf' });
  }
}
