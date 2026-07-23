import { Component, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { TranslocoModule } from '@jsverse/transloco';

import { ApiService } from '../../core/api.service';
import { OperationState } from '../../core/operation-state';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
import { ResultPanelComponent } from '../../shared/result-panel/result-panel.component';

/** Crop every page by trimming a margin off each edge (points or mm). Batch → ZIP. */
@Component({
  selector: 'app-crop-page',
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
        [title]="'pages.crop.title' | transloco"
        [description]="'pages.crop.description' | transloco"
      />

      <app-file-drop-zone
        [multiple]="true"
        accept=".pdf"
        [hint]="'pages.crop.hint' | transloco"
        (filesChange)="files.set($event)"
      />

      <p class="hint-note" role="note">{{ 'pages.crop.privacyLine' | transloco }}</p>
      @if (files().length > 1) {
        <p class="help">{{ 'pages.crop.batchNote' | transloco: { count: files().length } }}</p>
      }

      <div class="card form-grid">
        <div class="field">
          <label for="cr-top">{{ 'pages.crop.top' | transloco }}</label>
          <input id="cr-top" type="number" min="0" step="1" [formControl]="top" />
        </div>
        <div class="field">
          <label for="cr-right">{{ 'pages.crop.right' | transloco }}</label>
          <input id="cr-right" type="number" min="0" step="1" [formControl]="right" />
        </div>
        <div class="field">
          <label for="cr-bottom">{{ 'pages.crop.bottom' | transloco }}</label>
          <input id="cr-bottom" type="number" min="0" step="1" [formControl]="bottom" />
        </div>
        <div class="field">
          <label for="cr-left">{{ 'pages.crop.left' | transloco }}</label>
          <input id="cr-left" type="number" min="0" step="1" [formControl]="left" />
        </div>
        <div class="field">
          <label for="cr-unit">{{ 'pages.crop.unit' | transloco }}</label>
          <select id="cr-unit" [value]="unit()" (change)="unit.set($any($event.target).value)">
            <option value="pt">{{ 'pages.crop.unitPt' | transloco }}</option>
            <option value="mm">{{ 'pages.crop.unitMm' | transloco }}</option>
          </select>
          <span class="help">{{ 'pages.crop.unitHelp' | transloco }}</span>
        </div>
      </div>

      <div class="btn-row">
        <button
          type="button"
          class="btn btn-primary"
          [disabled]="!files().length || state.loading()"
          (click)="submit()"
        >
          {{ 'pages.crop.submit' | transloco }}
          @if (files().length) {
            · {{ 'common.fileCount' | transloco: { count: files().length } }}
          }
        </button>
      </div>

      <app-result-panel
        [loading]="state.loading()"
        [loadingLabel]="'pages.crop.loading' | transloco"
        [error]="state.error()"
        [result]="state.result()"
        (retry)="submit()"
      />
    </section>
  `,
})
export class CropPage {
  protected readonly files = signal<File[]>([]);
  protected readonly unit = signal<'pt' | 'mm'>('pt');
  protected readonly top = new FormControl(0, { nonNullable: true });
  protected readonly right = new FormControl(0, { nonNullable: true });
  protected readonly bottom = new FormControl(0, { nonNullable: true });
  protected readonly left = new FormControl(0, { nonNullable: true });
  protected readonly state = new OperationState();

  constructor(private readonly api: ApiService) {}

  submit(): void {
    if (!this.files().length) return;
    const fd = new FormData();
    for (const f of this.files()) fd.append('files', f, f.name);
    fd.append('top', String(this.top.value ?? 0));
    fd.append('right', String(this.right.value ?? 0));
    fd.append('bottom', String(this.bottom.value ?? 0));
    fd.append('left', String(this.left.value ?? 0));
    fd.append('unit', this.unit());
    this.state.run(this.api.crop(fd));
  }
}
