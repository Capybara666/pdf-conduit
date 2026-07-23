import { Component, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { TranslocoModule } from '@jsverse/transloco';

import { ApiService } from '../../core/api.service';
import { OperationState } from '../../core/operation-state';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
import { PasswordFieldComponent } from '../../shared/password-field/password-field.component';
import { ResultPanelComponent } from '../../shared/result-panel/result-panel.component';

/** Remove a known password from one or more PDFs. */
@Component({
  selector: 'app-unlock-page',
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
        [title]="'pages.unlock.title' | transloco"
        [description]="'pages.unlock.description' | transloco"
      />

      <app-file-drop-zone
        [multiple]="true"
        accept=".pdf"
        [hint]="'pages.unlock.hint' | transloco"
        (filesChange)="files.set($event)"
      />

      <p class="hint-note" role="note">{{ 'pages.unlock.privacyLine' | transloco }}</p>

      <div class="card form-grid">
        <div class="field">
          <label for="ul-pass">{{ 'pages.unlock.password' | transloco }}</label>
          <app-password-field inputId="ul-pass" autocomplete="off" [formControl]="password" />
          <span class="help">{{ 'pages.unlock.passwordHelp' | transloco }}</span>
          @if (password.invalid && password.touched) {
            <span class="err" aria-live="polite">{{ 'pages.unlock.passwordError' | transloco }}</span>
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
          {{ 'pages.unlock.submit' | transloco }}
          @if (files().length) {
            · {{ 'common.fileCount' | transloco: { count: files().length } }}
          }
        </button>
        <button type="button" class="btn" (click)="clear()">{{ 'common.clear' | transloco }}</button>
      </div>

      <app-result-panel
        [loading]="state.loading()"
        [loadingLabel]="'pages.unlock.loading' | transloco"
        [error]="state.error()"
        [result]="state.result()"
        (retry)="submit()"
      />
    </section>
  `,
})
export class UnlockPage {
  protected readonly files = signal<File[]>([]);
  protected readonly password = new FormControl('', { nonNullable: true, validators: [Validators.required] });
  protected readonly state = new OperationState();

  constructor(private readonly api: ApiService) {}

  // The password is never persisted (privacy), so unlock only needs an
  // explicit Clear — nothing to restore across a refresh.
  clear(): void {
    this.password.reset();
    this.files.set([]);
    this.state.reset();
  }

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
