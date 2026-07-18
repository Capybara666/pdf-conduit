import { Component, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { TranslocoModule } from '@jsverse/transloco';

import { ApiService } from '../../core/api.service';
import { OperationState } from '../../core/operation-state';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
import { ResultPanelComponent } from '../../shared/result-panel/result-panel.component';

/** Render PDF pages to PNG or JPG (a single image, or a ZIP for many). */
@Component({
  selector: 'app-to-images-page',
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
        [title]="'pages.toImages.title' | transloco"
        [description]="'pages.toImages.description' | transloco"
      />

      <app-file-drop-zone
        [multiple]="false"
        accept=".pdf"
        [hint]="'pages.toImages.hint' | transloco"
        (filesChange)="file.set($event.length ? $event[0] : null)"
      />

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
          <input id="ti-dpi" type="number" min="36" max="600" step="1" [formControl]="dpi" />
          <span class="help">{{ 'pages.toImages.dpiHelp' | transloco }}</span>
        </div>
        <div class="field">
          <label for="ti-pages">{{ 'pages.toImages.pages' | transloco }}</label>
          <input
            id="ti-pages"
            type="text"
            [formControl]="pages"
            [placeholder]="'pages.toImages.pagesPlaceholder' | transloco"
          />
        </div>
      </div>

      <div class="btn-row">
        <button
          type="button"
          class="btn btn-primary"
          [disabled]="!file() || dpi.invalid || state.loading()"
          (click)="submit()"
        >
          {{ 'pages.toImages.submit' | transloco }}
        </button>
      </div>

      <app-result-panel
        [loading]="state.loading()"
        [loadingLabel]="'pages.toImages.loading' | transloco"
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
