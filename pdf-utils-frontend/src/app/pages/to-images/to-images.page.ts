import { Component, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';

import { ApiService } from '../../core/api.service';
import { OperationState } from '../../core/operation-state';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
import { ResultPanelComponent } from '../../shared/result-panel/result-panel.component';

/** Render PDF pages to PNG or JPG (a single image, or a ZIP for many). */
@Component({
  selector: 'app-to-images-page',
  standalone: true,
  imports: [ReactiveFormsModule, FileDropZoneComponent, PageHeaderComponent, ResultPanelComponent],
  template: `
    <section class="op-page">
      <app-page-header title="To Images" description="Render PDF pages to PNG or JPG." />

      <app-file-drop-zone
        [multiple]="false"
        accept=".pdf"
        hint="One PDF (multiple pages → ZIP)."
        (filesChange)="file.set($event.length ? $event[0] : null)"
      />

      <div class="card form-grid">
        <div class="field">
          <label for="ti-format">Format</label>
          <select id="ti-format" [value]="format()" (change)="format.set($any($event.target).value)">
            <option value="PNG">PNG</option>
            <option value="JPG">JPG</option>
          </select>
        </div>
        <div class="field">
          <label for="ti-dpi">DPI</label>
          <input id="ti-dpi" type="number" min="36" max="600" step="1" [formControl]="dpi" />
          <span class="help">Higher = sharper and larger (36–600).</span>
        </div>
        <div class="field">
          <label for="ti-pages">Pages</label>
          <input id="ti-pages" type="text" [formControl]="pages" placeholder="e.g. 1-3 (blank = all)" />
        </div>
      </div>

      <div class="btn-row">
        <button
          type="button"
          class="btn btn-primary"
          [disabled]="!file() || dpi.invalid || state.loading()"
          (click)="submit()"
        >
          Render images
        </button>
      </div>

      <app-result-panel
        [loading]="state.loading()"
        loadingLabel="Rendering…"
        [error]="state.error()"
        [result]="state.result()"
      />
    </section>
  `,
})
export class ToImagesPage {
  protected readonly file = signal<File | null>(null);
  protected readonly format = signal<'PNG' | 'JPG'>('PNG');
  protected readonly dpi = new FormControl(150, {
    nonNullable: true,
    validators: [Validators.required, Validators.min(36), Validators.max(600)],
  });
  protected readonly pages = new FormControl('', { nonNullable: true });
  protected readonly state = new OperationState();

  constructor(private readonly api: ApiService) {}

  submit(): void {
    const f = this.file();
    if (!f || this.dpi.invalid) return;
    const fd = new FormData();
    fd.append('file', f, f.name);
    fd.append('format', this.format());
    fd.append('dpi', String(this.dpi.value));
    const p = this.pages.value.trim();
    if (p) fd.append('pages', p);
    this.state.run(this.api.toImages(fd));
  }
}
