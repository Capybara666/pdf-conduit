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

/** Extract the text content of a PDF to a text file. */
@Component({
  selector: 'app-to-text-page',
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
        [title]="'pages.toText.title' | transloco"
        [description]="'pages.toText.description' | transloco"
      />

      <app-file-drop-zone
        [multiple]="true"
        accept=".pdf"
        [hint]="'pages.toText.hint' | transloco"
        (filesChange)="files.set($event)"
      />

      @if (files().length > 1) {
        <p class="help">{{ 'pages.toText.batchNote' | transloco: { count: files().length } }}</p>
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
          <label for="tt-format">{{ 'pages.toText.format' | transloco }}</label>
          <select id="tt-format" [value]="format()" (change)="format.set($any($event.target).value)">
            <option value="TXT">{{ 'pages.toText.formatTxt' | transloco }}</option>
            <option value="DOCX">{{ 'pages.toText.formatDocx' | transloco }}</option>
          </select>
        </div>
        <div class="field">
          <label for="tt-pages">{{ 'pages.toText.pages' | transloco }}</label>
          <input
            id="tt-pages"
            type="text"
            [formControl]="pages"
            [placeholder]="'pages.toText.pagesPlaceholder' | transloco"
          />
        </div>
      </div>

      <div class="btn-row">
        <button type="button" class="btn btn-primary" [disabled]="!files().length || state.loading()" (click)="submit()">
          {{ 'pages.toText.submit' | transloco }}
          @if (files().length) {
            · {{ 'common.fileCount' | transloco: { count: files().length } }}
          }
        </button>
        <button type="button" class="btn" (click)="clear()">{{ 'common.clear' | transloco }}</button>
      </div>

      <app-op-progress
        [run]="state.tracker()"
        [label]="'pages.toText.loading' | transloco"
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
export class ToTextPage {
  protected readonly files = signal<File[]>([]);
  /** The lone file when exactly one is selected — drives the visual page-select grid. */
  protected readonly singleFile = computed(() => (this.files().length === 1 ? this.files()[0] : null));
  protected readonly format = signal<'TXT' | 'DOCX'>('TXT');
  protected readonly pages = new FormControl('', { nonNullable: true });
  protected readonly state = new OperationState();

  private readonly workState = inject(WorkStateService);

  constructor(private readonly api: ApiService) {
    this.workState.persist('to-text', { format: this.format, pages: this.pages });
  }

  clear(): void {
    this.workState.reset('to-text');
    this.files.set([]);
    this.state.reset();
  }

  submit(): void {
    const files = this.files();
    if (!files.length) return;
    const fd = new FormData();
    for (const f of files) fd.append('files', f, f.name);
    fd.append('format', this.format());
    const p = this.pages.value.trim();
    if (p) fd.append('pages', p);
    this.state.run(this.api.toText(fd));
  }
}
