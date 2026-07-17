import { Component, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';

import { ApiService } from '../../core/api.service';
import { OperationState } from '../../core/operation-state';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
import { ResultPanelComponent } from '../../shared/result-panel/result-panel.component';

/** Add AES password encryption to one or more PDFs. */
@Component({
  selector: 'app-protect-page',
  standalone: true,
  imports: [ReactiveFormsModule, FileDropZoneComponent, PageHeaderComponent, ResultPanelComponent],
  template: `
    <section class="op-page">
      <app-page-header title="Protect" description="Add password encryption to a PDF." />

      <app-file-drop-zone
        [multiple]="true"
        accept=".pdf"
        hint="One or more PDFs (several files → ZIP)."
        (filesChange)="files.set($event)"
      />

      <form class="card form-grid" [formGroup]="form">
        <div class="field">
          <label for="pr-user">User password</label>
          <input id="pr-user" type="password" formControlName="userPassword" autocomplete="new-password" />
          <span class="help">Required to open the document.</span>
          @if (form.controls.userPassword.invalid && form.controls.userPassword.touched) {
            <span class="err">A user password is required.</span>
          }
        </div>
        <div class="field">
          <label for="pr-owner">Owner password (optional)</label>
          <input id="pr-owner" type="password" formControlName="ownerPassword" autocomplete="new-password" />
          <span class="help">Controls permissions; defaults to the user password.</span>
        </div>
      </form>

      <div class="btn-row">
        <button
          type="button"
          class="btn btn-primary"
          [disabled]="!files().length || form.invalid || state.loading()"
          (click)="submit()"
        >
          Protect {{ files().length }} file{{ files().length === 1 ? '' : 's' }}
        </button>
      </div>

      <app-result-panel
        [loading]="state.loading()"
        loadingLabel="Encrypting…"
        [error]="state.error()"
        [result]="state.result()"
      />
    </section>
  `,
})
export class ProtectPage {
  protected readonly files = signal<File[]>([]);
  protected readonly form = new FormGroup({
    userPassword: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    ownerPassword: new FormControl('', { nonNullable: true }),
  });
  protected readonly state = new OperationState();

  constructor(private readonly api: ApiService) {}

  submit(): void {
    if (!this.files().length || this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const fd = new FormData();
    for (const f of this.files()) fd.append('files', f, f.name);
    fd.append('userPassword', this.form.controls.userPassword.value);
    const owner = this.form.controls.ownerPassword.value.trim();
    if (owner) fd.append('ownerPassword', owner);
    this.state.run(this.api.protect(fd));
  }
}
