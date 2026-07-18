import { Component, computed, inject, signal } from '@angular/core';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';

import { ApiError, RunResult } from '../../core/api.models';
import { ApiService } from '../../core/api.service';
import { downloadRunResult, formatBytes } from '../../core/download.util';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
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
  imports: [TranslocoModule, FileDropZoneComponent, PageHeaderComponent, SpinnerComponent],
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
          <ul class="reorder-list">
            @for (it of items(); track it.file; let i = $index) {
              <li
                [class.dragging]="dragIndex() === i"
                draggable="true"
                (dragstart)="dragIndex.set(i)"
                (dragover)="onDragOver($event, i)"
                (dragend)="dragIndex.set(null)"
              >
                <span class="grip" aria-hidden="true">⠿</span>
                <span class="idx">{{ i + 1 }}</span>
                <span class="name" [title]="it.file.name">{{ it.file.name }}</span>
                @if (isPdf(it.file)) {
                  <input
                    class="range-in"
                    type="text"
                    [value]="it.pages"
                    (input)="setPages(i, $any($event.target).value)"
                    [placeholder]="'pages.wizard.rangePlaceholder' | transloco"
                  />
                } @else {
                  <span class="hint-note">{{ (isImage(it.file) ? 'pages.wizard.typeImage' : 'pages.wizard.typeDocument') | transloco }}</span>
                }
                <button type="button" class="icon-btn" (click)="remove(i)" [attr.aria-label]="'common.remove' | transloco">✕</button>
              </li>
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

          @if (busy()) {
            <app-spinner [label]="progress()" />
          }
          @if (error()) {
            <p class="err">{{ error()!.message }}</p>
          }
          @if (result()) {
            <div class="done-box">
              <p class="filename">{{ result()!.filename }} ({{ formatBytes(result()!.blob.size) }})</p>
              <button type="button" class="btn btn-primary" (click)="download()">{{ 'common.download' | transloco }}</button>
            </div>
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
      .range-in {
        width: 130px;
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
export class WizardPage {
  private readonly transloco = inject(TranslocoService);
  protected readonly steps = STEP_KEYS;
  protected readonly formatBytes = formatBytes;

  protected readonly step = signal(0);
  protected readonly items = signal<WizardFile[]>([]);
  protected readonly dragIndex = signal<number | null>(null);
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

  isPdf(f: File): boolean {
    return f.type === 'application/pdf' || f.name.toLowerCase().endsWith('.pdf');
  }
  isImage(f: File): boolean {
    return f.type.startsWith('image/');
  }

  onFiles(files: File[]): void {
    this.items.set(files.map((file) => ({ file, pages: '' })));
  }

  setPages(i: number, value: string): void {
    const next = this.items().slice();
    next[i] = { ...next[i], pages: value };
    this.items.set(next);
  }

  remove(i: number): void {
    const next = this.items().slice();
    next.splice(i, 1);
    this.items.set(next);
  }

  onDragOver(ev: DragEvent, over: number): void {
    ev.preventDefault();
    const from = this.dragIndex();
    if (from === null || from === over) return;
    const next = this.items().slice();
    const [moved] = next.splice(from, 1);
    next.splice(over, 0, moved);
    this.items.set(next);
    this.dragIndex.set(over);
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
