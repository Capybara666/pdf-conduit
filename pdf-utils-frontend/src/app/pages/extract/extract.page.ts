import { Component, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';

import { ApiService } from '../../core/api.service';
import { OperationState } from '../../core/operation-state';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
import { ResultPanelComponent } from '../../shared/result-panel/result-panel.component';

/** Extract selected pages from a single PDF into one PDF, or one file per page. */
@Component({
  selector: 'app-extract-page',
  standalone: true,
  imports: [ReactiveFormsModule, FileDropZoneComponent, PageHeaderComponent, ResultPanelComponent],
  template: `
    <section class="op-page">
      <app-page-header title="Extract" description="Pull selected pages out of a PDF." />

      <app-file-drop-zone
        [multiple]="false"
        accept=".pdf"
        hint="One PDF."
        (filesChange)="file.set($event.length ? $event[0] : null)"
      />

      <div class="card form-grid">
        <div class="field">
          <label for="ex-pages">Pages</label>
          <input id="ex-pages" type="text" [formControl]="pages" placeholder="e.g. 1,3,5-8" />
          <span class="help">Blank = all pages. Syntax: <code>1</code>, <code>2-5</code>, <code>end-2</code>.</span>
        </div>
        <div class="field">
          <span class="field-label">Output</span>
          <label class="check">
            <input type="checkbox" [formControl]="separate" />
            Split into one file per page (ZIP)
          </label>
        </div>
      </div>

      <div class="btn-row">
        <button type="button" class="btn btn-primary" [disabled]="!file() || state.loading()" (click)="submit()">
          Extract
        </button>
      </div>

      <app-result-panel
        [loading]="state.loading()"
        loadingLabel="Extracting…"
        [error]="state.error()"
        [result]="state.result()"
      />
    </section>
  `,
})
export class ExtractPage {
  protected readonly file = signal<File | null>(null);
  protected readonly pages = new FormControl('', { nonNullable: true });
  protected readonly separate = new FormControl(false, { nonNullable: true });
  protected readonly state = new OperationState();

  constructor(private readonly api: ApiService) {}

  submit(): void {
    const f = this.file();
    if (!f) return;
    const fd = new FormData();
    fd.append('file', f, f.name);
    const p = this.pages.value.trim();
    if (p) fd.append('pages', p);
    fd.append('separate', String(this.separate.value));
    this.state.run(this.api.extract(fd));
  }
}
