import { Component, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { TranslocoModule } from '@jsverse/transloco';

import { ApiService } from '../../core/api.service';
import { OperationState } from '../../core/operation-state';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
import { PageGridComponent } from '../../shared/page-grid/page-grid.component';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
import { ResultPanelComponent } from '../../shared/result-panel/result-panel.component';

/** Rotate pages of one or more PDFs by 90/180/270 degrees. */
@Component({
  selector: 'app-rotate-page',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    TranslocoModule,
    FileDropZoneComponent,
    PageGridComponent,
    PageHeaderComponent,
    ResultPanelComponent,
  ],
  template: `
    <section class="op-page">
      <app-page-header
        [title]="'pages.rotate.title' | transloco"
        [description]="'pages.rotate.description' | transloco"
      />

      <app-file-drop-zone
        [multiple]="true"
        accept=".pdf"
        [hint]="'pages.rotate.hint' | transloco"
        (filesChange)="files.set($event)"
      />

      <div class="card form-grid">
        <div class="field">
          <span class="field-label">{{ 'pages.rotate.angle' | transloco }}</span>
          <div class="seg" role="group" [attr.aria-label]="'pages.rotate.angleAria' | transloco">
            @for (a of angles; track a) {
              <button type="button" [class.active]="angle() === a" (click)="angle.set(a)">{{ a }}°</button>
            }
          </div>
        </div>
        <div class="field">
          <label for="rt-pages">{{ 'pages.rotate.pages' | transloco }}</label>
          <input
            id="rt-pages"
            type="text"
            [formControl]="pages"
            [placeholder]="'pages.rotate.pagesPlaceholder' | transloco"
          />
          <span class="help">{{ 'pages.rotate.pagesHelp' | transloco }}</span>
        </div>
      </div>

      @if (files().length) {
        <p class="help pg-hint">{{ 'pageGrid.appliesToAll' | transloco }}</p>
        <app-page-grid
          mode="select"
          [file]="files()[0]"
          [range]="pages.value"
          (rangeChange)="pages.setValue($event)"
        />
      }

      <div class="btn-row">
        <button type="button" class="btn btn-primary" [disabled]="!files().length || state.loading()" (click)="submit()">
          {{ 'pages.rotate.submit' | transloco: { count: files().length } }}
        </button>
      </div>

      <app-result-panel
        [loading]="state.loading()"
        [loadingLabel]="'pages.rotate.loading' | transloco"
        [error]="state.error()"
        [result]="state.result()"
      />
    </section>
  `,
})
export class RotatePage {
  protected readonly angles = [90, 180, 270];
  protected readonly files = signal<File[]>([]);
  protected readonly angle = signal(90);
  protected readonly pages = new FormControl('', { nonNullable: true });
  protected readonly state = new OperationState();

  constructor(private readonly api: ApiService) {}

  submit(): void {
    if (!this.files().length) return;
    const fd = new FormData();
    for (const f of this.files()) fd.append('files', f, f.name);
    fd.append('angle', String(this.angle()));
    const p = this.pages.value.trim();
    if (p) fd.append('pages', p);
    this.state.run(this.api.rotate(fd));
  }
}
