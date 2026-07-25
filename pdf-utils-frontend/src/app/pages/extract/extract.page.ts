import { Component, computed, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { TranslocoModule } from '@jsverse/transloco';

import { ApiService } from '../../core/api.service';
import { OperationState } from '../../core/operation-state';
import { WorkStateService } from '../../core/work-state.service';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
import { OpProgressComponent } from '../../shared/op-progress/op-progress.component';
import { PageGridComponent } from '../../shared/page-grid/page-grid.component';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
import { ResultPanelComponent } from '../../shared/result-panel/result-panel.component';

/** How the selected pages are written out. */
type ExtractMode = 'one' | 'perPage' | 'every';

/**
 * Extract selected pages: into one PDF, into one file per page, or split every N pages
 * (both multi-file modes come back as a ZIP).
 */
@Component({
  selector: 'app-extract-page',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    TranslocoModule,
    FileDropZoneComponent,
    PageGridComponent,
    OpProgressComponent,
    PageHeaderComponent,
    ResultPanelComponent,
  ],
  template: `
    <section class="op-page">
      <app-page-header
        [title]="'pages.extract.title' | transloco"
        [description]="'pages.extract.description' | transloco"
      />

      <app-file-drop-zone
        [multiple]="true"
        accept=".pdf"
        [hint]="'pages.extract.hint' | transloco"
        (filesChange)="files.set($event)"
      />

      @if (singleFile()) {
        <app-page-grid
          mode="select"
          [file]="singleFile()"
          [range]="pages.value"
          (rangeChange)="pages.setValue($event)"
        />
      } @else if (files().length > 1) {
        <p class="help">{{ 'pages.extract.batchNote' | transloco: { count: files().length } }}</p>
      }

      <div class="card form-grid">
        <div class="field">
          <label for="ex-pages">{{ 'pages.extract.pages' | transloco }}</label>
          <input
            id="ex-pages"
            type="text"
            [formControl]="pages"
            [placeholder]="'pages.extract.pagesPlaceholder' | transloco"
          />
          <span class="help">
            {{ 'pages.extract.pagesHelp' | transloco }}
            <code>1</code>, <code>2-5</code>, <code>end-2</code>.
          </span>
        </div>
        <div class="field">
          <span class="field-label" id="ex-mode-label">{{ 'pages.extract.output' | transloco }}</span>
          <div class="seg" role="group" aria-labelledby="ex-mode-label">
            <button
              type="button"
              [class.active]="mode() === 'one'"
              [attr.aria-pressed]="mode() === 'one'"
              (click)="mode.set('one')"
            >
              {{ 'pages.extract.modeOne' | transloco }}
            </button>
            <button
              type="button"
              [class.active]="mode() === 'perPage'"
              [attr.aria-pressed]="mode() === 'perPage'"
              (click)="mode.set('perPage')"
            >
              {{ 'pages.extract.modePerPage' | transloco }}
            </button>
            <button
              type="button"
              [class.active]="mode() === 'every'"
              [attr.aria-pressed]="mode() === 'every'"
              (click)="mode.set('every')"
            >
              {{ 'pages.extract.modeEvery' | transloco }}
            </button>
          </div>
          <span class="help">{{ 'pages.extract.modeHelp.' + mode() | transloco }}</span>
        </div>

        @if (mode() === 'every') {
          <div class="field">
            <label for="ex-every">{{ 'pages.extract.every' | transloco }}</label>
            <input
              id="ex-every"
              type="number"
              min="1"
              [max]="maxEvery"
              step="1"
              [value]="every()"
              (input)="setEvery($any($event.target).value)"
            />
            <span class="help">{{ 'pages.extract.everyHelp' | transloco: { max: maxEvery } }}</span>
          </div>
        }
      </div>

      <div class="btn-row">
        <button type="button" class="btn btn-primary" [disabled]="!files().length || state.loading()" (click)="submit()">
          {{ 'pages.extract.submit' | transloco }}
          @if (files().length) {
            · {{ 'common.fileCount' | transloco: { count: files().length } }}
          }
        </button>
        <button type="button" class="btn" (click)="clear()">{{ 'common.clear' | transloco }}</button>
      </div>

      <app-op-progress
        [run]="state.tracker()"
        [label]="'pages.extract.loading' | transloco"
        (cancel)="state.cancel()"
        (dismiss)="state.dismiss()"
      />

      <app-result-panel
        [error]="state.error()"
        [result]="state.result()"
        (retry)="submit()"
      />
    </section>
  `,
})
export class ExtractPage {
  /** Upper bound for "every N pages" — beyond this the split is one part anyway. */
  protected readonly maxEvery = 999;

  protected readonly files = signal<File[]>([]);
  /** The lone file when exactly one is selected — drives the visual page-select grid. */
  protected readonly singleFile = computed(() => (this.files().length === 1 ? this.files()[0] : null));
  protected readonly pages = new FormControl('', { nonNullable: true });
  protected readonly mode = signal<ExtractMode>('one');
  /** Pages per output file in "every N pages" mode; always kept in 1..maxEvery. */
  protected readonly every = signal(10);
  protected readonly state = new OperationState();

  private readonly workState = inject(WorkStateService);

  constructor(private readonly api: ApiService) {
    this.workState.persist('extract', { pages: this.pages, mode: this.mode, every: this.every });
  }

  /** Clamp typed input so an empty / silly value can never reach the API. */
  setEvery(raw: string): void {
    const n = Math.floor(Number(raw));
    this.every.set(Number.isFinite(n) ? Math.min(this.maxEvery, Math.max(1, n)) : 1);
  }

  clear(): void {
    this.workState.reset('extract');
    this.files.set([]);
    this.state.reset();
  }

  submit(): void {
    const fs = this.files();
    if (!fs.length) return;
    const fd = new FormData();
    for (const f of fs) fd.append('files', f, f.name);
    const p = this.pages.value.trim();
    if (p) fd.append('pages', p);
    // The page range keeps its meaning in every mode: it picks WHAT is extracted, the mode only
    // decides how the picked pages are written out.
    if (this.mode() === 'perPage') fd.append('separate', 'true');
    if (this.mode() === 'every') fd.append('splitEvery', String(this.every()));
    this.state.run(this.api.extract(fd));
  }
}
