import { DecimalPipe } from '@angular/common';
import { Component, computed, effect, inject, signal } from '@angular/core';
import {
  FormControl,
  ReactiveFormsModule,
  ValidationErrors,
  ValidatorFn,
  Validators,
} from '@angular/forms';
import { TranslocoModule } from '@jsverse/transloco';

import { ApiService } from '../../core/api.service';
import { CapabilitiesService, MIN_RENDER_DPI } from '../../core/capabilities.service';
import { OperationState } from '../../core/operation-state';
import { WorkStateService } from '../../core/work-state.service';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
import { OpProgressComponent } from '../../shared/op-progress/op-progress.component';
import { PageGridComponent } from '../../shared/page-grid/page-grid.component';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
import { ResultPanelComponent } from '../../shared/result-panel/result-panel.component';

/** Render PDF pages to PNG or JPG (a single image, or a ZIP for many). */
@Component({
  selector: 'app-to-images-page',
  standalone: true,
  imports: [
    DecimalPipe,
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
        [title]="'pages.toImages.title' | transloco"
        [description]="'pages.toImages.description' | transloco"
      />

      <app-file-drop-zone
        [multiple]="true"
        accept=".pdf"
        [hint]="'pages.toImages.hint' | transloco"
        (filesChange)="files.set($event)"
      />

      <p class="hint-note" role="note">{{ 'pages.toImages.privacyLine' | transloco }}</p>
      @if (files().length > 1) {
        <p class="help">{{ 'pages.toImages.batchNote' | transloco: { count: files().length } }}</p>
      }
      @if (singleFile()) {
        <app-page-grid
          mode="select"
          [file]="singleFile()"
          [range]="pages.value"
          (rangeChange)="pages.setValue($event)"
        />
      }

      <div class="card form-grid">
        <div class="field">
          <label for="ti-format">{{ 'pages.toImages.format' | transloco }}</label>
          <select id="ti-format" [value]="format()" (change)="format.set($any($event.target).value)">
            <option value="PNG">PNG</option>
            <option value="JPG">JPG</option>
          </select>
        </div>
        <div class="field">
          <label for="ti-dpi">{{ 'pages.toImages.dpi' | transloco }}</label>
          <input id="ti-dpi" type="number" [min]="minDpi" [max]="maxDpi()" step="1" [formControl]="dpi" />
          <span class="help">{{
            'pages.toImages.dpiHelpRange' | transloco: { min: minDpi, max: maxDpi() }
          }}</span>
          @if (dpi.invalid && dpi.touched) {
            <span class="err" aria-live="polite">{{
              'pages.toImages.dpiErrorRange' | transloco: { min: minDpi, max: maxDpi() }
            }}</span>
          }
        </div>
        @if (format() === 'JPG') {
          <div class="field">
            <label for="ti-quality">{{ 'pages.toImages.quality' | transloco }} <span class="hint-note">{{ quality() | number: '1.2-2' }}</span></label>
            <div class="range-row">
              <input id="ti-quality" type="range" min="0.05" max="1" step="0.05"
                     [value]="quality()" (input)="quality.set(+$any($event.target).value)" />
              <output>{{ quality() | number: '1.2-2' }}</output>
            </div>
            <span class="help">{{ 'pages.toImages.qualityHelp' | transloco }}</span>
          </div>
        }
        <div class="field">
          <label for="ti-pages">{{ 'pages.toImages.pages' | transloco }}</label>
          <input
            id="ti-pages"
            type="text"
            [formControl]="pages"
            [placeholder]="'pages.toImages.pagesPlaceholder' | transloco"
          />
        </div>
        <div class="field">
          <span class="field-label">{{ 'pages.toImages.color' | transloco }}</span>
          @if (format() === 'PNG') {
            <label class="check">
              <input type="checkbox" [checked]="transparentBg()"
                     (change)="transparentBg.set($any($event.target).checked)" />
              {{ 'pages.toImages.transparent' | transloco }}
            </label>
            <span class="help">{{ 'pages.toImages.transparentHelp' | transloco }}</span>
          }
          <label class="check">
            <input type="checkbox" [checked]="grayscale()"
                   (change)="grayscale.set($any($event.target).checked)" />
            {{ 'pages.toImages.grayscale' | transloco }}
          </label>
        </div>
      </div>

      <div class="btn-row">
        <button
          type="button"
          class="btn btn-primary"
          [disabled]="!files().length || dpi.invalid || state.loading()"
          (click)="submit()"
        >
          {{ 'pages.toImages.submit' | transloco }}
          @if (files().length) {
            · {{ 'common.fileCount' | transloco: { count: files().length } }}
          }
        </button>
        <button type="button" class="btn" (click)="clear()">{{ 'common.clear' | transloco }}</button>
      </div>

      <app-op-progress
        [run]="state.tracker()"
        [label]="'pages.toImages.loading' | transloco"
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
export class ToImagesPage {
  protected readonly files = signal<File[]>([]);
  /** The lone file when exactly one is selected — drives the visual page-select grid. */
  protected readonly singleFile = computed(() => (this.files().length === 1 ? this.files()[0] : null));
  protected readonly format = signal<'PNG' | 'JPG'>('PNG');
  protected readonly quality = signal(0.8);
  /** Transparent (alpha) background — PNG only; JPEG has no alpha so it is not sent. */
  protected readonly transparentBg = signal(false);
  protected readonly grayscale = signal(false);

  /** Lowest DPI offered — a UI floor, not a server limit. */
  protected readonly minDpi = MIN_RENDER_DPI;
  /**
   * Highest DPI the SERVER will render at, straight off `GET /api/capabilities`
   * (`environment.maxDpi` until it answers, and if it fails or predates the
   * field). The `max` attribute, the validator and the help/error copy all read
   * this one signal, so the form cannot call a value valid that the backend
   * refuses with 422 `output_too_large`.
   */
  protected readonly maxDpi = inject(CapabilitiesService).maxDpi;

  /**
   * Written as a closure rather than `Validators.max(...)` because the ceiling
   * arrives asynchronously: a validator baked at construction time would freeze
   * the fallback. This reads the signal on every validation run.
   */
  private readonly withinServerDpi: ValidatorFn = (control): ValidationErrors | null => {
    const value = control.value;
    const max = this.maxDpi();
    return typeof value === 'number' && Number.isFinite(value) && value > max
      ? { max: { max, actual: value } }
      : null;
  };

  protected readonly dpi = new FormControl(150, {
    nonNullable: true,
    validators: [Validators.required, Validators.min(MIN_RENDER_DPI), this.withinServerDpi],
  });
  protected readonly pages = new FormControl('', { nonNullable: true });
  protected readonly state = new OperationState();

  private readonly workState = inject(WorkStateService);

  constructor(private readonly api: ApiService) {
    // Re-run validation when the advertised ceiling lands (or changes), so a
    // value typed during the pre-response window is re-judged against the real
    // limit instead of staying "valid" until the next keystroke.
    effect(() => {
      this.maxDpi();
      this.dpi.updateValueAndValidity({ emitEvent: false });
    });
    this.workState.persist('to-images', {
      format: this.format,
      quality: this.quality,
      transparentBg: this.transparentBg,
      grayscale: this.grayscale,
      dpi: this.dpi,
      pages: this.pages,
    });
  }

  clear(): void {
    this.workState.reset('to-images');
    this.files.set([]);
    this.state.reset();
  }

  submit(): void {
    if (!this.files().length || this.dpi.invalid) {
      this.dpi.markAsTouched();
      return;
    }
    const fd = new FormData();
    for (const f of this.files()) fd.append('files', f, f.name);
    fd.append('format', this.format());
    fd.append('dpi', String(this.dpi.value));
    if (this.format() === 'JPG') fd.append('quality', String(this.quality()));
    // Transparent background is PNG-only (JPEG has no alpha); grayscale applies to both.
    if (this.format() === 'PNG' && this.transparentBg()) fd.append('transparent', 'true');
    if (this.grayscale()) fd.append('grayscale', 'true');
    const p = this.pages.value.trim();
    if (p) fd.append('pages', p);
    this.state.run(this.api.toImages(fd));
  }
}
