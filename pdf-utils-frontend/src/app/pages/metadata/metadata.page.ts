import { Component, effect, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';

import { ApiService } from '../../core/api.service';
import { ApiError } from '../../core/api.models';
import { errorCopyKeys } from '../../core/error-copy';
import { OperationState } from '../../core/operation-state';
import { WorkStateService } from '../../core/work-state.service';
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
        [multiple]="true"
        accept=".pdf"
        [hint]="'pages.metadata.hint' | transloco"
        (filesChange)="onFiles($event)"
      />

      @if (files().length > 1) {
        <p class="hint-note">{{ 'pages.metadata.batchNote' | transloco }}</p>
      }

      <div class="btn-row">
        <button
          type="button"
          class="btn"
          [disabled]="files().length !== 1 || reading()"
          (click)="readCurrent()"
        >
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
        <div class="field" [class.field-disabled]="strip()">
          <label for="md-title">{{ 'pages.metadata.titleField' | transloco }}</label>
          <input id="md-title" type="text" formControlName="title" />
        </div>
        <div class="field" [class.field-disabled]="strip()">
          <label for="md-author">{{ 'pages.metadata.author' | transloco }}</label>
          <input id="md-author" type="text" formControlName="author" />
        </div>
        <div class="field" [class.field-disabled]="strip()">
          <label for="md-subject">{{ 'pages.metadata.subject' | transloco }}</label>
          <input id="md-subject" type="text" formControlName="subject" />
        </div>
        <div class="field" [class.field-disabled]="strip()">
          <label for="md-keywords">{{ 'pages.metadata.keywords' | transloco }}</label>
          <input id="md-keywords" type="text" formControlName="keywords" />
        </div>
        <div class="field full">
          <label class="check">
            <input type="checkbox" [checked]="strip()" (change)="toggleStrip($event)" />
            {{ 'pages.metadata.strip' | transloco }}
          </label>
        </div>
      </form>

      <div class="btn-row">
        <button type="button" class="btn btn-primary" [disabled]="!files().length || state.loading()" (click)="submit()">
          {{ (strip() ? 'pages.metadata.submitStrip' : 'pages.metadata.submitApply') | transloco }}
          @if (files().length) {
            · {{ 'common.fileCount' | transloco: { count: files().length } }}
          }
        </button>
        <button type="button" class="btn" (click)="clear()">{{ 'common.clear' | transloco }}</button>
      </div>

      <app-result-panel
        [loading]="state.loading()"
        [loadingLabel]="'pages.metadata.loading' | transloco"
        [error]="state.error()"
        [result]="state.result()"
        (retry)="submit()"
      />
    </section>
  `,
  styles: [
    `
      /* When strip-all is on the individual fields are ignored — make that
         visually obvious: dim the whole field and show the inputs as disabled. */
      .field-disabled {
        opacity: 0.5;
      }
      .field-disabled label {
        cursor: not-allowed;
      }
      .field input:disabled {
        cursor: not-allowed;
      }
    `,
  ],
})
export class MetadataPage {
  protected readonly files = signal<File[]>([]);
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
  private readonly workState = inject(WorkStateService);

  constructor(private readonly api: ApiService) {
    this.workState.persist('metadata', { strip: this.strip, form: this.form });
    // Strip-all ignores the individual fields, so disable them (greys out the
    // inputs and blocks typing) while it's on; re-enable when it's unchecked.
    // Reactive-forms disabling is the source of truth — the inputs get the
    // native `disabled` state and the wrapping `.field-disabled` class dims them.
    effect(() => {
      if (this.strip()) {
        this.form.disable({ emitEvent: false });
      } else {
        this.form.enable({ emitEvent: false });
      }
    });
  }

  /** Friendly, code-aware headline for a failed manual read (never a raw stack). */
  get readErrorText(): string {
    const e = this.readError();
    return e ? this.transloco.translate(errorCopyKeys(e).titleKey) : '';
  }

  onFiles(files: File[]): void {
    this.files.set(files);
    this.readError.set(null);
    this.clearFields();
    // Auto-prefill the moment a single PDF is added (mirrors the desktop app).
    // The read/preview only makes sense for one file, so with 0 or >1 selected
    // we leave the fields blank + editable. "Read current" stays as a manual
    // refresh; a silent failure just leaves the fields blank rather than
    // shouting about a protected/damaged file.
    if (files.length === 1) this.read(files[0], true);
  }

  toggleStrip(ev: Event): void {
    this.strip.set((ev.target as HTMLInputElement).checked);
  }

  readCurrent(): void {
    const list = this.files();
    if (list.length === 1) this.read(list[0], false);
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

  clear(): void {
    this.workState.reset('metadata');
    this.files.set([]);
    this.strip.set(false);
    this.clearFields();
    this.readError.set(null);
    this.state.reset();
  }

  submit(): void {
    const list = this.files();
    if (!list.length) return;
    const fd = new FormData();
    for (const f of list) fd.append('files', f, f.name);
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
