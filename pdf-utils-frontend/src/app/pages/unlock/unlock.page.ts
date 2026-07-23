import { Component, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { TranslocoModule } from '@jsverse/transloco';

import { ApiService } from '../../core/api.service';
import { OperationState } from '../../core/operation-state';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
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
          <div class="pw-row">
            <input
              id="ul-pass"
              [type]="showPass() ? 'text' : 'password'"
              [formControl]="password"
              autocomplete="off"
            />
            <button
              type="button"
              class="btn pw-toggle"
              [attr.aria-pressed]="showPass()"
              [attr.aria-label]="(showPass() ? 'common.hidePassword' : 'common.showPassword') | transloco"
              (click)="showPass.set(!showPass())"
            >
              {{ (showPass() ? 'common.hide' : 'common.show') | transloco }}
            </button>
          </div>
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
  styles: [
    `
      .pw-row {
        display: flex;
        gap: 0.4rem;
        align-items: stretch;
      }
      .pw-row input {
        flex: 1 1 auto;
        min-width: 0;
      }
      .pw-toggle {
        flex: 0 0 auto;
        white-space: nowrap;
      }
    `,
  ],
})
export class UnlockPage {
  protected readonly files = signal<File[]>([]);
  protected readonly showPass = signal(false);
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
