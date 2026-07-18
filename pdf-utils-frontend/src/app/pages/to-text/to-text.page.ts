import { Component, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { TranslocoModule } from '@jsverse/transloco';

import { ApiService } from '../../core/api.service';
import { OperationState } from '../../core/operation-state';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
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
        </button>
      </div>

      <app-result-panel
        [loading]="state.loading()"
        [loadingLabel]="'pages.toText.loading' | transloco"
        [error]="state.error()"
        [result]="state.result()"
        (retry)="submit()"
      />
    </section>
  `,
})
export class ToTextPage {
  protected readonly files = signal<File[]>([]);
  protected readonly format = signal<'TXT' | 'DOCX'>('TXT');
  protected readonly pages = new FormControl('', { nonNullable: true });
  protected readonly state = new OperationState();

  constructor(private readonly api: ApiService) {}

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
