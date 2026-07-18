import { Component, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { TranslocoModule } from '@jsverse/transloco';

import { ApiService } from '../../core/api.service';
import { OperationState } from '../../core/operation-state';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
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

      <form class="card form-grid" [formGroup]="form">
        <div class="field">
          <label for="pr-user">{{ 'pages.protect.userPassword' | transloco }}</label>
          <input id="pr-user" type="password" formControlName="userPassword" autocomplete="new-password" />
          <span class="help">{{ 'pages.protect.userPasswordHelp' | transloco }}</span>
          @if (form.controls.userPassword.invalid && form.controls.userPassword.touched) {
            <span class="err">{{ 'pages.protect.userPasswordError' | transloco }}</span>
          }
        </div>
        <div class="field">
          <label for="pr-owner">{{ 'pages.protect.ownerPassword' | transloco }}</label>
          <input id="pr-owner" type="password" formControlName="ownerPassword" autocomplete="new-password" />
          <span class="help">{{ 'pages.protect.ownerPasswordHelp' | transloco }}</span>
        </div>
      </form>

      <div class="btn-row">
        <button
          type="button"
          class="btn btn-primary"
          [disabled]="!files().length || form.invalid || state.loading()"
          (click)="submit()"
        >
          {{ 'pages.protect.submit' | transloco: { count: files().length } }}
        </button>
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
