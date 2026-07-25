import { Component, computed, inject, signal } from '@angular/core';
import { TranslocoModule } from '@jsverse/transloco';

import { ApiService } from '../../core/api.service';
import { CapabilitiesService } from '../../core/capabilities.service';
import { OperationState } from '../../core/operation-state';
import { WorkStateService } from '../../core/work-state.service';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
import { OpProgressComponent } from '../../shared/op-progress/op-progress.component';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
import { ResultPanelComponent } from '../../shared/result-panel/result-panel.component';

type PageSize = 'FIT' | 'A4' | 'A3' | 'LETTER';

/** Convert images / office docs to PDF — one PDF per input (several → ZIP). */
@Component({
  selector: 'app-to-pdf-page',
  standalone: true,
  imports: [TranslocoModule, FileDropZoneComponent, OpProgressComponent, PageHeaderComponent, ResultPanelComponent],
  template: `
    <section class="op-page">
      <app-page-header
        [title]="'pages.toPdf.title' | transloco"
        [description]="'pages.toPdf.description' | transloco"
      />

      <app-file-drop-zone
        [multiple]="true"
        [accept]="accept()"
        [hint]="(officeEnabled() ? 'pages.toPdf.hint' : 'pages.toPdf.hintNoOffice') | transloco"
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
        <button type="button" class="btn" (click)="clear()">{{ 'common.clear' | transloco }}</button>
      </div>

      <app-op-progress
        [run]="state.tracker()"
        [label]="'pages.toPdf.loading' | transloco"
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
export class ToPdfPage {
  protected readonly files = signal<File[]>([]);
  protected readonly pageSize = signal<PageSize>('FIT');
  protected readonly state = new OperationState();

  private readonly workState = inject(WorkStateService);
  private readonly capabilities = inject(CapabilitiesService);

  /** Whether the server converts office/document inputs (docx/xlsx/txt/md/html/…). */
  protected readonly officeEnabled = this.capabilities.officeEnabled;

  /**
   * Accepted input types. All office/document extensions (everything the backend
   * classifies `Kind.OFFICE`, incl. txt/md/html) are dropped when the server has
   * office conversion disabled — they would only be rejected with a 415.
   */
  protected readonly accept = computed(() =>
    this.officeEnabled()
      ? 'image/*,.docx,.odt,.rtf,.txt,.md,.markdown,.html,.htm,.xlsx,.pptx,.pdf'
      : 'image/*,.pdf',
  );

  constructor(private readonly api: ApiService) {
    this.workState.persist('to-pdf', { pageSize: this.pageSize });
  }

  clear(): void {
    this.workState.reset('to-pdf');
    this.files.set([]);
    this.state.reset();
  }

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
