import { Component, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { TranslocoModule } from '@jsverse/transloco';

import { ApiService } from '../../core/api.service';
import { OperationState } from '../../core/operation-state';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
import { ResultPanelComponent } from '../../shared/result-panel/result-panel.component';

/** Compress one or more PDFs toward a target size (e.g. "5MB"). */
@Component({
  selector: 'app-compress-page',
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
        [title]="'pages.compress.title' | transloco"
        [description]="'pages.compress.description' | transloco"
      />

      <app-file-drop-zone
        [multiple]="true"
        accept=".pdf"
        [hint]="'pages.compress.hint' | transloco"
        (filesChange)="files.set($event)"
      />

      <div class="card form-grid">
        <div class="field">
          <label for="cp-target">{{ 'pages.compress.target' | transloco }}</label>
          <input
            id="cp-target"
            type="text"
            [formControl]="targetSize"
            [placeholder]="'pages.compress.targetPlaceholder' | transloco"
          />
          <span class="help">
            {{ 'pages.compress.targetHelp1' | transloco }}
            <code>KB</code>/<code>MB</code>
            {{ 'pages.compress.targetHelp2' | transloco }}
          </span>
          @if (targetSize.invalid && targetSize.touched) {
            <span class="err">{{ 'pages.compress.targetError' | transloco }} <code>5MB</code>.</span>
          }
        </div>
      </div>

      <div class="btn-row">
        <button
          type="button"
          class="btn btn-primary"
          [disabled]="!files().length || targetSize.invalid || state.loading()"
          (click)="submit()"
        >
          {{ 'pages.compress.submit' | transloco: { count: files().length } }}
        </button>
      </div>

      <app-result-panel
        [loading]="state.loading()"
        [loadingLabel]="'pages.compress.loading' | transloco"
        [error]="state.error()"
        [result]="state.result()"
        (retry)="submit()"
      />
    </section>
  `,
})
export class CompressPage {
  protected readonly files = signal<File[]>([]);
  // Matches "5MB", "800 KB", "1.5mb", or a bare byte count.
  protected readonly targetSize = new FormControl('5MB', {
    nonNullable: true,
    validators: [Validators.required, Validators.pattern(/^\s*\d+(\.\d+)?\s*(b|kb|mb|gb)?\s*$/i)],
  });
  protected readonly state = new OperationState();

  constructor(private readonly api: ApiService) {}

  submit(): void {
    if (!this.files().length || this.targetSize.invalid) return;
    const fd = new FormData();
    for (const f of this.files()) fd.append('files', f, f.name);
    fd.append('targetSize', this.targetSize.value.trim());
    this.state.run(this.api.compress(fd));
  }
}
