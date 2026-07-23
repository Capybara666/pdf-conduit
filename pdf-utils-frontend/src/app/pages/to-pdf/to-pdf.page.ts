import { Component, signal } from '@angular/core';
import { TranslocoModule } from '@jsverse/transloco';

import { ApiService } from '../../core/api.service';
import { OperationState } from '../../core/operation-state';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
import { ResultPanelComponent } from '../../shared/result-panel/result-panel.component';

type PageSize = 'FIT' | 'A4' | 'A3' | 'LETTER';

/** Convert images / office docs to PDF — one PDF per input (several → ZIP). */
@Component({
  selector: 'app-to-pdf-page',
  standalone: true,
  imports: [TranslocoModule, FileDropZoneComponent, PageHeaderComponent, ResultPanelComponent],
  template: `
    <section class="op-page">
      <app-page-header
        [title]="'pages.toPdf.title' | transloco"
        [description]="'pages.toPdf.description' | transloco"
      />

      <app-file-drop-zone
        [multiple]="true"
        accept="image/*,.docx,.odt,.rtf,.txt,.md,.markdown,.html,.htm,.xlsx,.pptx,.pdf"
        [hint]="'pages.toPdf.hint' | transloco"
        (filesChange)="files.set($event)"
      />

      <div class="card form-grid">
        <div class="field">
          <label for="tp-size">{{ 'pages.toPdf.pageSize' | transloco }}</label>
          <select id="tp-size" [value]="pageSize()" (change)="onSize($event)">
            <option value="FIT">{{ 'pages.toPdf.sizeFit' | transloco }}</option>
            <option value="A4">A4</option>
            <option value="A3">A3</option>
            <option value="LETTER">{{ 'pages.toPdf.sizeLetter' | transloco }}</option>
          </select>
          <span class="help">{{ 'pages.toPdf.sizeHelp' | transloco }}</span>
        </div>
      </div>

      <div class="btn-row">
        <button type="button" class="btn btn-primary" [disabled]="!files().length || state.loading()" (click)="submit()">
          {{ 'pages.toPdf.submit' | transloco }}
          @if (files().length) {
            · {{ 'common.fileCount' | transloco: { count: files().length } }}
          }
        </button>
      </div>

      <app-result-panel
        [loading]="state.loading()"
        [loadingLabel]="'pages.toPdf.loading' | transloco"
        [error]="state.error()"
        [result]="state.result()"
        (retry)="submit()"
      />
    </section>
  `,
})
export class ToPdfPage {
  protected readonly files = signal<File[]>([]);
  protected readonly pageSize = signal<PageSize>('FIT');
  protected readonly state = new OperationState();

  constructor(private readonly api: ApiService) {}

  onSize(ev: Event): void {
    this.pageSize.set((ev.target as HTMLSelectElement).value as PageSize);
  }

  submit(): void {
    if (!this.files().length) return;
    const fd = new FormData();
    for (const f of this.files()) fd.append('files', f, f.name);
    fd.append('pageSize', this.pageSize());
    this.state.run(this.api.toPdf(fd));
  }
}
