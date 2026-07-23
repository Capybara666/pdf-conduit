import { Component, computed, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { TranslocoModule } from '@jsverse/transloco';

import { ApiService } from '../../core/api.service';
import { OperationState } from '../../core/operation-state';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
import { PageGridComponent } from '../../shared/page-grid/page-grid.component';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
import { ResultPanelComponent } from '../../shared/result-panel/result-panel.component';

/** Extract selected pages from a single PDF into one PDF, or one file per page. */
@Component({
  selector: 'app-extract-page',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    TranslocoModule,
    FileDropZoneComponent,
    PageGridComponent,
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
          <span class="field-label">{{ 'pages.extract.output' | transloco }}</span>
          <label class="check">
            <input type="checkbox" [formControl]="separate" />
            {{ 'pages.extract.separate' | transloco }}
          </label>
        </div>
      </div>

      <div class="btn-row">
        <button type="button" class="btn btn-primary" [disabled]="!files().length || state.loading()" (click)="submit()">
          {{ 'pages.extract.submit' | transloco }}
          @if (files().length) {
            · {{ 'common.fileCount' | transloco: { count: files().length } }}
          }
        </button>
      </div>

      <app-result-panel
        [loading]="state.loading()"
        [loadingLabel]="'pages.extract.loading' | transloco"
        [error]="state.error()"
        [result]="state.result()"
        (retry)="submit()"
      />
    </section>
  `,
})
export class ExtractPage {
  protected readonly files = signal<File[]>([]);
  /** The lone file when exactly one is selected — drives the visual page-select grid. */
  protected readonly singleFile = computed(() => (this.files().length === 1 ? this.files()[0] : null));
  protected readonly pages = new FormControl('', { nonNullable: true });
  protected readonly separate = new FormControl(false, { nonNullable: true });
  protected readonly state = new OperationState();

  constructor(private readonly api: ApiService) {}

  submit(): void {
    const fs = this.files();
    if (!fs.length) return;
    const fd = new FormData();
    for (const f of fs) fd.append('files', f, f.name);
    const p = this.pages.value.trim();
    if (p) fd.append('pages', p);
    fd.append('separate', String(this.separate.value));
    this.state.run(this.api.extract(fd));
  }
}
