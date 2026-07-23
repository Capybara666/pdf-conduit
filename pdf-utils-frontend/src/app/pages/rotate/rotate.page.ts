import { Component, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { TranslocoModule } from '@jsverse/transloco';

import { ApiService } from '../../core/api.service';
import { OperationState } from '../../core/operation-state';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
import { PageGridComponent } from '../../shared/page-grid/page-grid.component';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
import { ResultPanelComponent } from '../../shared/result-panel/result-panel.component';

/** Rotate pages of one or more PDFs by 90/180/270 degrees. */
@Component({
  selector: 'app-rotate-page',
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
        [title]="'pages.rotate.title' | transloco"
        [description]="'pages.rotate.description' | transloco"
      />

      <app-file-drop-zone
        [multiple]="true"
        accept=".pdf"
        [hint]="'pages.rotate.hint' | transloco"
        (filesChange)="files.set($event)"
      />

      <div class="card form-grid">
        <div class="field">
          <span class="field-label">{{ 'pages.rotate.angle' | transloco }}</span>
          <div class="seg" role="group" [attr.aria-label]="'pages.rotate.angleAria' | transloco">
            <button type="button" [class.active]="angle() === 90" [attr.aria-pressed]="angle() === 90" (click)="angle.set(90)"
                    style="display:flex;flex-direction:column;align-items:center;gap:.35rem;min-width:6.5rem">
              <svg viewBox="0 0 24 24" width="26" height="26" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <rect x="9.5" y="8.5" width="5" height="7" rx="1" />
                <path d="M12 4.5 A 7.5 7.5 0 0 1 19.5 12" />
                <path d="M17 9.5 L19.5 12 L22 9.5" />
              </svg>
              <span>{{ 'pages.rotate.dir90' | transloco }}</span>
            </button>
            <button type="button" [class.active]="angle() === 180" [attr.aria-pressed]="angle() === 180" (click)="angle.set(180)"
                    style="display:flex;flex-direction:column;align-items:center;gap:.35rem;min-width:6.5rem">
              <svg viewBox="0 0 24 24" width="26" height="26" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <rect x="9.5" y="8.5" width="5" height="7" rx="1" />
                <path d="M12 4.5 A 7.5 7.5 0 0 1 12 19.5" />
                <path d="M14.5 17 L12 19.5 L14.5 22" />
              </svg>
              <span>{{ 'pages.rotate.dir180' | transloco }}</span>
            </button>
            <button type="button" [class.active]="angle() === 270" [attr.aria-pressed]="angle() === 270" (click)="angle.set(270)"
                    style="display:flex;flex-direction:column;align-items:center;gap:.35rem;min-width:6.5rem">
              <svg viewBox="0 0 24 24" width="26" height="26" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <rect x="9.5" y="8.5" width="5" height="7" rx="1" />
                <path d="M12 4.5 A 7.5 7.5 0 0 0 4.5 12" />
                <path d="M2 9.5 L4.5 12 L7 9.5" />
              </svg>
              <span>{{ 'pages.rotate.dir270' | transloco }}</span>
            </button>
          </div>
        </div>
        <div class="field">
          <label for="rt-pages">{{ 'pages.rotate.pages' | transloco }}</label>
          <input
            id="rt-pages"
            type="text"
            [formControl]="pages"
            [placeholder]="'pages.rotate.pagesPlaceholder' | transloco"
          />
        </div>
      </div>

      @if (files().length) {
        <p class="help pg-hint">{{ 'pageGrid.appliesToAll' | transloco }}</p>
        <app-page-grid
          mode="select"
          [file]="files()[0]"
          [range]="pages.value"
          (rangeChange)="pages.setValue($event)"
        />
      }

      <div class="btn-row">
        <button type="button" class="btn btn-primary" [disabled]="!files().length || state.loading()" (click)="submit()">
          {{ 'pages.rotate.submit' | transloco }}
          @if (files().length) {
            · {{ 'common.fileCount' | transloco: { count: files().length } }}
          }
        </button>
      </div>

      <app-result-panel
        [loading]="state.loading()"
        [loadingLabel]="'pages.rotate.loading' | transloco"
        [error]="state.error()"
        [result]="state.result()"
        (retry)="submit()"
      />
    </section>
  `,
})
export class RotatePage {
  protected readonly files = signal<File[]>([]);
  protected readonly angle = signal(90);
  protected readonly pages = new FormControl('', { nonNullable: true });
  protected readonly state = new OperationState();

  constructor(private readonly api: ApiService) {}

  submit(): void {
    if (!this.files().length) return;
    const fd = new FormData();
    for (const f of this.files()) fd.append('files', f, f.name);
    fd.append('angle', String(this.angle()));
    const p = this.pages.value.trim();
    if (p) fd.append('pages', p);
    this.state.run(this.api.rotate(fd));
  }
}
