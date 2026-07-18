import { Component, OnDestroy, computed, inject, signal } from '@angular/core';
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
    TranslocoModule,
    FileDropZoneComponent,
    PageGridComponent,
    PageHeaderComponent,
    ResultPanelComponent,
    SpinnerComponent,
  ],
  template: `
    <section class="op-page">
      <app-page-header
        [title]="'pages.wizard.title' | transloco"
        [description]="'pages.wizard.description' | transloco"
      />

      <ol class="stepper">
        @for (s of steps; track s; let i = $index) {
          <li [class.active]="i === step()" [class.done]="i < step()">
            <span class="dot">{{ i < step() ? '✓' : i + 1 }}</span>
            <span class="lbl">{{ s | transloco }}</span>
          </li>
        }
      </ol>

      <div class="card">
        <!-- Step 1: select -->
        @if (step() === 0) {
          <h2 class="step-h">{{ 'pages.wizard.selectTitle' | transloco }}</h2>
          <app-file-drop-zone
            [multiple]="true"
            accept=".pdf,image/*,.docx,.odt,.rtf,.txt,.xlsx,.pptx"
            [hint]="'pages.wizard.selectHint' | transloco"
            (filesChange)="onFiles($event)"
          />
        }

        <!-- Step 2: arrange + page ranges -->
        @if (step() === 1) {
          <h2 class="step-h">{{ 'pages.wizard.arrangeTitle' | transloco }}</h2>
          <p class="hint-note">{{ 'pages.wizard.arrangeHint' | transloco }}</p>
          <ul class="file-list" role="list">
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
                  (dragstart)="dragIndex.set(i)"
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
          <label class="check" style="margin-bottom:0.75rem">
            <input type="checkbox" [checked]="compress()" (change)="compress.set($any($event.target).checked)" />
            {{ 'pages.wizard.compressToggle' | transloco }}
          </label>
          @if (compress()) {
            <div class="form-grid">
              <div class="field">
                <label for="wz-target">{{ 'pages.wizard.target' | transloco }}</label>
                <input id="wz-target" type="text" [value]="targetSize()" (input)="targetSize.set($any($event.target).value)" [placeholder]="'pages.wizard.targetPlaceholder' | transloco" />
              </div>
            </div>
          }
        }

        <!-- Step 5: export -->
        @if (step() === 4) {
          <h2 class="step-h">{{ 'pages.wizard.reviewTitle' | transloco }}</h2>
          <ul class="summary">
            <li><span>{{ 'pages.wizard.sumFiles' | transloco }}</span><b>{{ items().length }}</b></li>
            <li><span>{{ 'pages.wizard.sumOrder' | transloco }}</span><b>{{ orderNames() }}</b></li>
            <li><span>{{ 'pages.wizard.sumImageSize' | transloco }}</span><b>{{ pageSize() }}</b></li>
            <li><span>{{ 'pages.wizard.sumCompress' | transloco }}</span><b>{{ compress() ? targetSize() : ('pages.wizard.compressNo' | transloco) }}</b></li>
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
          <button type="button" class="btn btn-primary" [disabled]="!items().length || busy()" (click)="runExport()">
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
        gap: 0.45rem;
        color: var(--text-muted);
        font-size: 0.85rem;
      }
      .stepper li.active {
        color: var(--text);
        font-weight: 600;
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
        margin: 0 0 1rem;
        font-size: 1.1rem;
      }
      .hint-note {
        color: var(--text-muted);
        font-size: 0.85rem;
        margin: 0 0 0.9rem;
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
      @media (max-width: 560px) {
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
      .summary li {
        display: flex;
        flex-direction: column;
        padding: 0.5rem 0.75rem;
        background: var(--surface-2);
        border-radius: 8px;
      }
      .summary span {
        font-size: 0.72rem;
        text-transform: uppercase;
        color: var(--text-muted);
      }
      .done-box {
        border: 1px solid var(--success);
        border-radius: var(--radius);
        padding: 1rem;
        margin-top: 0.5rem;
      }
      .filename {
        font-weight: 600;
        margin: 0 0 0.75rem;
        word-break: break-all;
      }
      .err {
        color: var(--danger);
      }
    `,
  ],
})
export class WizardPage implements OnDestroy {
  private readonly transloco = inject(TranslocoService);
  protected readonly steps = STEP_KEYS;
  protected readonly formatBytes = formatBytes;

  protected readonly step = signal(0);
  protected readonly items = signal<WizardFile[]>([]);
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
  protected readonly targetSize = signal('5MB');

  protected readonly busy = signal(false);
  protected readonly progress = signal('');
  protected readonly error = signal<ApiError | null>(null);
  protected readonly result = signal<RunResult | null>(null);

  protected readonly orderNames = computed(() =>
    this.items()
      .map((i) => i.file.name)
      .join(', '),
  );

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
    this.revokeUrls();
    this.thumbs.clear();
    this.pageCounts.clear();
    this.expandedFile.set(null);
    this.items.set(files.map((file) => ({ file, pages: '' })));
    for (const file of files) void this.buildThumb(file);
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

  canNext(): boolean {
    if (this.busy()) return false;
    if (this.step() === 0) return this.items().length > 0;
    return true;
  }

  next(): void {
    if (this.canNext()) this.step.set(Math.min(this.step() + 1, this.steps.length - 1));
  }
  back(): void {
    this.step.set(Math.max(this.step() - 1, 0));
  }
  restart(): void {
    this.revokeUrls();
    this.thumbs.clear();
    this.pageCounts.clear();
    this.expandedFile.set(null);
    this.items.set([]);
    this.step.set(0);
    this.result.set(null);
    this.error.set(null);
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

      if (this.compress() && this.targetSize().trim()) {
        this.progress.set(this.transloco.translate('pages.wizard.compressing'));
        const fd = new FormData();
        fd.append('files', this.asFile(out), out.filename);
        fd.append('targetSize', this.targetSize().trim());
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
