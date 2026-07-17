import { Component, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';

import { ApiService } from '../../core/api.service';
import { OperationState } from '../../core/operation-state';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
import { ResultPanelComponent } from '../../shared/result-panel/result-panel.component';

/** Extract the text content of a PDF to a text file. */
@Component({
  selector: 'app-to-text-page',
  standalone: true,
  imports: [ReactiveFormsModule, FileDropZoneComponent, PageHeaderComponent, ResultPanelComponent],
  template: `
    <section class="op-page">
      <app-page-header title="To Text" description="Extract the text content of a PDF." />

      <app-file-drop-zone
        [multiple]="false"
        accept=".pdf"
        hint="One PDF."
        (filesChange)="file.set($event.length ? $event[0] : null)"
      />

      <div class="card form-grid">
        <div class="field">
          <label for="tt-format">Format</label>
          <select id="tt-format" [value]="format()" (change)="format.set($any($event.target).value)">
            <option value="TXT">Plain text (.txt)</option>
          </select>
        </div>
        <div class="field">
          <label for="tt-pages">Pages</label>
          <input id="tt-pages" type="text" [formControl]="pages" placeholder="e.g. 1-3 (blank = all)" />
        </div>
      </div>

      <div class="btn-row">
        <button type="button" class="btn btn-primary" [disabled]="!file() || state.loading()" (click)="submit()">
          Extract text
        </button>
      </div>

      <app-result-panel
        [loading]="state.loading()"
        loadingLabel="Extracting text…"
        [error]="state.error()"
        [result]="state.result()"
      />
    </section>
  `,
})
export class ToTextPage {
  protected readonly file = signal<File | null>(null);
  protected readonly format = signal<'TXT'>('TXT');
  protected readonly pages = new FormControl('', { nonNullable: true });
  protected readonly state = new OperationState();

  constructor(private readonly api: ApiService) {}

  submit(): void {
    const f = this.file();
    if (!f) return;
    const fd = new FormData();
    fd.append('file', f, f.name);
    fd.append('format', this.format());
    const p = this.pages.value.trim();
    if (p) fd.append('pages', p);
    this.state.run(this.api.toText(fd));
  }
}
