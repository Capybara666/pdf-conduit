import { Component, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';

import { ApiService } from '../../core/api.service';
import { OperationState } from '../../core/operation-state';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
import { ResultPanelComponent } from '../../shared/result-panel/result-panel.component';

/** Compress one or more PDFs toward a target size (e.g. "5MB"). */
@Component({
  selector: 'app-compress-page',
  standalone: true,
  imports: [ReactiveFormsModule, FileDropZoneComponent, PageHeaderComponent, ResultPanelComponent],
  template: `
    <section class="op-page">
      <app-page-header title="Compress" description="Shrink a PDF toward a target size." />

      <app-file-drop-zone
        [multiple]="true"
        accept=".pdf"
        hint="One or more PDFs (several files → ZIP)."
        (filesChange)="files.set($event)"
      />

      <div class="card form-grid">
        <div class="field">
          <label for="cp-target">Target size</label>
          <input id="cp-target" type="text" [formControl]="targetSize" placeholder="e.g. 5MB, 800KB" />
          <span class="help">Accepts <code>KB</code>/<code>MB</code> or a raw byte count.</span>
          @if (targetSize.invalid && targetSize.touched) {
            <span class="err">Enter a size such as <code>5MB</code>.</span>
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
          Compress {{ files().length }} file{{ files().length === 1 ? '' : 's' }}
        </button>
      </div>

      <app-result-panel
        [loading]="state.loading()"
        loadingLabel="Compressing…"
        [error]="state.error()"
        [result]="state.result()"
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
