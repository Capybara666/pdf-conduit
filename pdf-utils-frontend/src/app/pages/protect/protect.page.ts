import { Component, inject, signal } from '@angular/core';
import {
  AbstractControl,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { TranslocoModule } from '@jsverse/transloco';

import { ApiService } from '../../core/api.service';
import { OperationState } from '../../core/operation-state';
import { WorkStateService } from '../../core/work-state.service';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
import { PasswordFieldComponent } from '../../shared/password-field/password-field.component';
import { ResultPanelComponent } from '../../shared/result-panel/result-panel.component';

/** Add AES password encryption to one or more PDFs. */
@Component({
  selector: 'app-protect-page',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    TranslocoModule,
    FileDropZoneComponent,
    PageHeaderComponent,
    PasswordFieldComponent,
    ResultPanelComponent,
  ],
  template: `
    <section class="op-page">
      <app-page-header
        [title]="'pages.protect.title' | transloco"
        [description]="'pages.protect.description' | transloco"
      />

      <app-file-drop-zone
        [multiple]="true"
        accept=".pdf"
        [hint]="'pages.protect.hint' | transloco"
        (filesChange)="files.set($event)"
      />

      <p class="hint-note" role="note">{{ 'pages.protect.privacyLine' | transloco }}</p>

      <form class="card form-grid" [formGroup]="form">
        <div class="field">
          <label for="pr-user">{{ 'pages.protect.userPassword' | transloco }}</label>
          <app-password-field
            inputId="pr-user"
            autocomplete="new-password"
            formControlName="userPassword"
          />
          <span class="help">{{ 'pages.protect.userPasswordHelp' | transloco }}</span>
          @if (form.controls.userPassword.invalid && form.controls.userPassword.touched) {
            <span class="err" aria-live="polite">{{ 'pages.protect.userPasswordError' | transloco }}</span>
          }
        </div>
        <div class="field">
          <label for="pr-confirm">{{ 'pages.protect.confirmPassword' | transloco }}</label>
          <app-password-field
            inputId="pr-confirm"
            autocomplete="new-password"
            formControlName="confirmPassword"
          />
          @if (form.errors?.['mismatch'] && form.controls.confirmPassword.touched) {
            <span class="err" aria-live="polite">{{ 'pages.protect.confirmError' | transloco }}</span>
          }
        </div>
        <div class="field">
          <label for="pr-owner">{{ 'pages.protect.ownerPassword' | transloco }}</label>
          <app-password-field
            inputId="pr-owner"
            autocomplete="new-password"
            formControlName="ownerPassword"
          />
          <span class="help">{{ 'pages.protect.ownerPasswordHelp' | transloco }}</span>
        </div>
        <div class="field">
          <label for="pr-strength">{{ 'pages.protect.encryption' | transloco }}</label>
          <select id="pr-strength" formControlName="keyLength">
            <option value="128">{{ 'pages.protect.encryption128' | transloco }}</option>
            <option value="256">{{ 'pages.protect.encryption256' | transloco }}</option>
          </select>
          <span class="help">{{ 'pages.protect.encryptionHelp' | transloco }}</span>
        </div>
      </form>

      <div class="btn-row">
        <button
          type="button"
          class="btn btn-primary"
          [disabled]="!files().length || form.invalid || state.loading()"
          (click)="submit()"
        >
          {{ 'pages.protect.submit' | transloco }}
          @if (files().length) {
            · {{ 'common.fileCount' | transloco: { count: files().length } }}
          }
        </button>
        <button type="button" class="btn" (click)="clear()">{{ 'common.clear' | transloco }}</button>
      </div>

      <app-result-panel
        [loading]="state.loading()"
        [loadingLabel]="'pages.protect.loading' | transloco"
        [error]="state.error()"
        [result]="state.result()"
        (retry)="submit()"
      />
    </section>
  `,
})
export class ProtectPage {
  protected readonly files = signal<File[]>([]);
  protected readonly form = new FormGroup(
    {
      userPassword: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
      confirmPassword: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
      ownerPassword: new FormControl('', { nonNullable: true }),
      keyLength: new FormControl('128', { nonNullable: true }),
    },
    { validators: [passwordsMatch] },
  );
  protected readonly state = new OperationState();
  private readonly workState = inject(WorkStateService);

  // Passwords are never persisted (privacy); only the non-sensitive key length.
  constructor(private readonly api: ApiService) {
    this.workState.persist('protect', { keyLength: this.form.controls.keyLength });
  }

  clear(): void {
    this.workState.reset('protect');
    this.form.reset();
    this.files.set([]);
    this.state.reset();
  }

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
    fd.append('keyLength', this.form.controls.keyLength.value);
    this.state.run(this.api.protect(fd));
  }
}

/**
 * Cross-field validator: the confirm field must equal the user password.
 * A typo here would permanently lock the file, so a mismatch blocks submit.
 */
function passwordsMatch(group: AbstractControl): ValidationErrors | null {
  const user = group.get('userPassword')?.value ?? '';
  const confirm = group.get('confirmPassword')?.value ?? '';
  return user === confirm ? null : { mismatch: true };
}
