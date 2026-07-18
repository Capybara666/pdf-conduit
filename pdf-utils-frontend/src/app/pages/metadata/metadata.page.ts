import { Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';

import { ApiService } from '../../core/api.service';
import { ApiError } from '../../core/api.models';
import { errorCopyKeys } from '../../core/error-copy';
import { OperationState } from '../../core/operation-state';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
import { ResultPanelComponent } from '../../shared/result-panel/result-panel.component';
import { SpinnerComponent } from '../../shared/spinner/spinner.component';

/** Read, edit or strip a PDF's document info. */
@Component({
  selector: 'app-metadata-page',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    TranslocoModule,
    FileDropZoneComponent,
    PageHeaderComponent,
    ResultPanelComponent,
    SpinnerComponent,
  ],
  template: `
    <section class="op-page">
      <app-page-header
        [title]="'pages.metadata.title' | transloco"
        [description]="'pages.metadata.description' | transloco"
      />

      <app-file-drop-zone
        [multiple]="false"
        accept=".pdf"
        [hint]="'pages.metadata.hint' | transloco"
        (filesChange)="onFile($event.length ? $event[0] : null)"
      />

      <div class="btn-row">
        <button type="button" class="btn" [disabled]="!file() || reading()" (click)="readCurrent()">
          {{ 'pages.metadata.readCurrent' | transloco }}
        </button>
        @if (reading()) {
          <app-spinner [label]="'pages.metadata.reading' | transloco" />
        }
        @if (readError()) {
          <span class="err">{{ readErrorText }}</span>
        }
      </div>

      <form class="card form-grid" [formGroup]="form">
        <div class="field">
          <label for="md-title">{{ 'pages.metadata.titleField' | transloco }}</label>
          <input id="md-title" type="text" formControlName="title" [attr.disabled]="strip() ? '' : null" />
        </div>
        <div class="field">
          <label for="md-author">{{ 'pages.metadata.author' | transloco }}</label>
          <input id="md-author" type="text" formControlName="author" [attr.disabled]="strip() ? '' : null" />
        </div>
        <div class="field">
          <label for="md-subject">{{ 'pages.metadata.subject' | transloco }}</label>
          <input id="md-subject" type="text" formControlName="subject" [attr.disabled]="strip() ? '' : null" />
        </div>
        <div class="field">
          <label for="md-keywords">{{ 'pages.metadata.keywords' | transloco }}</label>
          <input id="md-keywords" type="text" formControlName="keywords" [attr.disabled]="strip() ? '' : null" />
        </div>
        <div class="field full">
          <label class="check">
            <input type="checkbox" [checked]="strip()" (change)="toggleStrip($event)" />
            {{ 'pages.metadata.strip' | transloco }}
          </label>
        </div>
      </form>

      <div class="btn-row">
        <button type="button" class="btn btn-primary" [disabled]="!file() || state.loading()" (click)="submit()">
          {{ (strip() ? 'pages.metadata.submitStrip' : 'pages.metadata.submitApply') | transloco }}
        </button>
      </div>

      <app-result-panel
        [loading]="state.loading()"
        [loadingLabel]="'pages.metadata.loading' | transloco"
        [error]="state.error()"
        [result]="state.result()"
      />
    </section>
  `,
})
export class MetadataPage {
  protected readonly file = signal<File | null>(null);
  protected readonly reading = signal(false);
  protected readonly readError = signal<ApiError | null>(null);
  protected readonly strip = signal(false);
  protected readonly form = new FormGroup({
    title: new FormControl('', { nonNullable: true }),
    author: new FormControl('', { nonNullable: true }),
    subject: new FormControl('', { nonNullable: true }),
    keywords: new FormControl('', { nonNullable: true }),
  });
  protected readonly state = new OperationState();
  private readonly transloco = inject(TranslocoService);

  constructor(private readonly api: ApiService) {}

  /** Friendly, code-aware headline for a failed manual read (never a raw stack). */
  get readErrorText(): string {
    const e = this.readError();
    return e ? this.transloco.translate(errorCopyKeys(e).titleKey) : '';
  }

  onFile(f: File | null): void {
    this.file.set(f);
    this.readError.set(null);
    this.clearFields();
    // Auto-prefill the moment a PDF is added (mirrors the desktop app). "Read
    // current" stays as a manual refresh; a silent failure just leaves the
    // fields blank + editable rather than shouting about a protected/damaged file.
    if (f) this.read(f, true);
  }

  toggleStrip(ev: Event): void {
    this.strip.set((ev.target as HTMLInputElement).checked);
  }

  readCurrent(): void {
    const f = this.file();
    if (f) this.read(f, false);
  }

  private read(f: File, silent: boolean): void {
    this.reading.set(true);
    this.readError.set(null);
    const fd = new FormData();
    fd.append('file', f, f.name);
    this.api.readMetadata(fd).subscribe({
      next: (dto) => {
        this.form.setValue({
          title: dto.title ?? '',
          author: dto.author ?? '',
          subject: dto.subject ?? '',
          keywords: dto.keywords ?? '',
        });
        this.reading.set(false);
      },
      error: (e) => {
        if (!silent) {
          this.readError.set(e instanceof ApiError ? e : new ApiError('unknown', String(e), 0));
        }
        this.reading.set(false);
      },
    });
  }

  private clearFields(): void {
    this.form.setValue({ title: '', author: '', subject: '', keywords: '' });
  }

  submit(): void {
    const f = this.file();
    if (!f) return;
    const fd = new FormData();
    fd.append('file', f, f.name);
    if (this.strip()) {
      fd.append('strip', 'true');
    } else {
      const v = this.form.getRawValue();
      if (v.title.trim()) fd.append('title', v.title.trim());
      if (v.author.trim()) fd.append('author', v.author.trim());
      if (v.subject.trim()) fd.append('subject', v.subject.trim());
      if (v.keywords.trim()) fd.append('keywords', v.keywords.trim());
    }
    this.state.run(this.api.updateMetadata(fd));
  }
}
