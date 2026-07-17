import { Component, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';

import { ApiService } from '../../core/api.service';
import { OperationState } from '../../core/operation-state';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
import { ResultPanelComponent } from '../../shared/result-panel/result-panel.component';

/** Remove a known password from one or more PDFs. */
@Component({
  selector: 'app-unlock-page',
  standalone: true,
  imports: [ReactiveFormsModule, FileDropZoneComponent, PageHeaderComponent, ResultPanelComponent],
  template: `
    <section class="op-page">
      <app-page-header title="Unlock" description="Remove a known password from a PDF." />

      <app-file-drop-zone
        [multiple]="true"
        accept=".pdf"
        hint="One or more protected PDFs (several files → ZIP)."
        (filesChange)="files.set($event)"
      />

      <div class="card form-grid">
        <div class="field">
          <label for="ul-pass">Password</label>
          <input id="ul-pass" type="password" [formControl]="password" autocomplete="off" />
          <span class="help">The current password used to open the PDF(s).</span>
          @if (password.invalid && password.touched) {
            <span class="err">A password is required.</span>
          }
        </div>
      </div>

      <div class="btn-row">
        <button
          type="button"
          class="btn btn-primary"
          [disabled]="!files().length || password.invalid || state.loading()"
          (click)="submit()"
        >
          Unlock {{ files().length }} file{{ files().length === 1 ? '' : 's' }}
        </button>
      </div>

      <app-result-panel
        [loading]="state.loading()"
        loadingLabel="Unlocking…"
        [error]="state.error()"
        [result]="state.result()"
      />
    </section>
  `,
})
export class UnlockPage {
  protected readonly files = signal<File[]>([]);
  protected readonly password = new FormControl('', { nonNullable: true, validators: [Validators.required] });
  protected readonly state = new OperationState();

  constructor(private readonly api: ApiService) {}

  submit(): void {
    if (!this.files().length || this.password.invalid) {
      this.password.markAsTouched();
      return;
    }
    const fd = new FormData();
    for (const f of this.files()) fd.append('files', f, f.name);
    fd.append('password', this.password.value);
    this.state.run(this.api.unlock(fd));
  }
}
