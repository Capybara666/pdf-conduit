import { Component, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { TranslocoModule } from '@jsverse/transloco';

import { ApiService } from '../../core/api.service';
import { OperationState } from '../../core/operation-state';
import { WorkStateService } from '../../core/work-state.service';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
import { ResultPanelComponent } from '../../shared/result-panel/result-panel.component';

/**
 * OCR a scanned / image-only PDF into a searchable PDF: the server renders each page, runs
 * Tesseract, and adds an invisible text layer so the visual page is unchanged but the text
 * becomes selectable and searchable. Single input → a single searchable PDF.
 */
@Component({
  selector: 'app-ocr-page',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    TranslocoModule,
    FileDropZoneComponent,
    PageHeaderComponent,
    ResultPanelComponent,
  ],
  template: `
    <section class="op-page">
      <app-page-header
        [title]="'pages.ocr.title' | transloco"
        [description]="'pages.ocr.description' | transloco"
      />

      <app-file-drop-zone
        [multiple]="false"
        accept=".pdf,.png,.jpg,.jpeg,.tif,.tiff,.bmp,.gif,.webp"
        [hint]="'pages.ocr.hint' | transloco"
        (filesChange)="files.set($event)"
      />

      <div class="card form-grid">
        <div class="field">
          <label for="ocr-langs">{{ 'pages.ocr.languages' | transloco }}</label>
          <input
            id="ocr-langs"
            type="text"
            [formControl]="languages"
            [placeholder]="'pages.ocr.languagesPlaceholder' | transloco"
          />
          <span class="help">{{ 'pages.ocr.languagesHelp' | transloco }}</span>
        </div>
      </div>

      <div class="btn-row">
        <button
          type="button"
          class="btn btn-primary"
          [disabled]="!files().length || state.loading()"
          (click)="submit()"
        >
          {{ 'pages.ocr.submit' | transloco }}
        </button>
        <button type="button" class="btn" (click)="clear()">{{ 'common.clear' | transloco }}</button>
      </div>

      <app-result-panel
        [loading]="state.loading()"
        [loadingLabel]="'pages.ocr.loading' | transloco"
        [error]="state.error()"
        [result]="state.result()"
        (retry)="submit()"
      />
    </section>
  `,
})
export class OcrPage {
  protected readonly files = signal<File[]>([]);
  protected readonly languages = new FormControl('', { nonNullable: true });
  protected readonly state = new OperationState();

  private readonly workState = inject(WorkStateService);

  constructor(private readonly api: ApiService) {
    this.workState.persist('ocr', { languages: this.languages });
  }

  clear(): void {
    this.workState.reset('ocr');
    this.files.set([]);
    this.state.reset();
  }

  submit(): void {
    const file = this.files()[0];
    if (!file) return;
    const fd = new FormData();
    fd.append('file', file, file.name);
    const langs = this.languages.value.trim();
    if (langs) fd.append('languages', langs);
    this.state.run(this.api.ocr(fd));
  }
}
