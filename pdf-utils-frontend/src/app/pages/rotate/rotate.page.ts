import { Component, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { TranslocoModule } from '@jsverse/transloco';

import { ApiService } from '../../core/api.service';
import { OperationState } from '../../core/operation-state';
import { WorkStateService } from '../../core/work-state.service';
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
          <div class="rotate-picker">
            <div class="rotate-picker__main">
              <div class="seg rotate-seg" role="group" [attr.aria-label]="'pages.rotate.angleAria' | transloco">
                <button type="button" [class.active]="angle() === 90" [attr.aria-pressed]="angle() === 90" (click)="angle.set(90)">90°</button>
                <button type="button" [class.active]="angle() === 180" [attr.aria-pressed]="angle() === 180" (click)="angle.set(180)">180°</button>
                <button type="button" [class.active]="angle() === 270" [attr.aria-pressed]="angle() === 270" (click)="angle.set(270)">270°</button>
              </div>
              <div class="rotate-preview" aria-hidden="true">
                <svg class="rotate-glyph" viewBox="0 -6 100 120" width="72" height="86"
                     fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"
                     [style.transform]="'rotate(' + angle() + 'deg)'">
                  <!-- 'top' indicator (an up-arrow above the page) -->
                  <path d="M50 4 V13 M45 9 L50 4 L55 9" stroke="var(--accent)" stroke-width="3.5" />
                  <!-- portrait page with a dog-eared top-right corner -->
                  <path d="M25 18 H62 L78 34 V104 H25 Z" />
                  <path d="M62 18 V34 H78" />
                  <!-- large asymmetric 'F' marker -->
                  <text x="50" y="82" text-anchor="middle" font-size="46" font-weight="800"
                        fill="var(--accent)" stroke="none" font-family="inherit">F</text>
                </svg>
              </div>
            </div>
            <p class="rotate-caption">{{ 'pages.rotate.dir' + angle() | transloco }}</p>
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
          {{ 'pages.rotate.submit' | transloco }}
          @if (files().length) {
            · {{ 'common.fileCount' | transloco: { count: files().length } }}
          }
        </button>
        <button type="button" class="btn" (click)="clear()">{{ 'common.clear' | transloco }}</button>
      </div>

      <app-result-panel
        [loading]="state.loading()"
        [loadingLabel]="'pages.rotate.loading' | transloco"
        [error]="state.error()"
        [result]="state.result()"
        (retry)="submit()"
      />
    </section>
  `,
  styles: [
    `
      .rotate-picker {
        display: flex;
        flex-direction: column;
        gap: 0.55rem;
      }
      /* Selector + preview share one row and one vertical centre line. */
      .rotate-picker__main {
        display: flex;
        align-items: center;
        gap: 1.5rem;
        flex-wrap: wrap;
      }
      /* Force the three angle segments onto one equal-width row (never 2 + 1). */
      .rotate-seg {
        display: flex;
        flex-wrap: nowrap;
        flex: 1 1 12rem;
        min-width: 11rem;
      }
      .rotate-seg button {
        flex: 1 1 0;
        text-align: center;
        padding-left: 0.5rem;
        padding-right: 0.5rem;
      }
      .rotate-caption {
        margin: 0;
        font-size: 0.8rem;
        color: var(--text-muted);
      }
      .rotate-preview {
        flex: 0 0 auto;
        display: flex;
        align-items: center;
        justify-content: center;
        width: 6rem;
        color: var(--text);
      }
      .rotate-glyph {
        display: block;
        transform-origin: 50% 50%;
        transition: transform 0.4s var(--ease-standard, ease);
      }
    `,
  ],
})
export class RotatePage {
  protected readonly files = signal<File[]>([]);
  protected readonly angle = signal(90);
  protected readonly pages = new FormControl('', { nonNullable: true });
  protected readonly state = new OperationState();
  private readonly workState = inject(WorkStateService);

  constructor(private readonly api: ApiService) {
    this.workState.persist('rotate', { angle: this.angle, pages: this.pages });
  }

  clear(): void {
    this.workState.reset('rotate');
    this.files.set([]);
    this.state.reset();
  }

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
