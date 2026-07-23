import { Component, signal } from '@angular/core';
import { TranslocoModule } from '@jsverse/transloco';

import { ApiService } from '../../core/api.service';
import { OperationState } from '../../core/operation-state';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
import { ResultPanelComponent } from '../../shared/result-panel/result-panel.component';

type NupLayout = '2up' | '4up' | '6up' | '8up' | '9up';

/** N-up / booklet imposition: place several source pages onto each output sheet. */
@Component({
  selector: 'app-nup-page',
  standalone: true,
  imports: [TranslocoModule, FileDropZoneComponent, PageHeaderComponent, ResultPanelComponent],
  template: `
    <section class="op-page">
      <app-page-header
        [title]="'pages.nup.title' | transloco"
        [description]="'pages.nup.description' | transloco"
      />

      <app-file-drop-zone
        [multiple]="true"
        accept=".pdf"
        [hint]="'pages.nup.hint' | transloco"
        (filesChange)="files.set($event)"
      />

      <p class="hint-note" role="note">{{ 'pages.nup.privacyLine' | transloco }}</p>
      @if (files().length > 1) {
        <p class="help">{{ 'pages.nup.batchNote' | transloco: { count: files().length } }}</p>
      }

      <div class="card form-grid">
        <div class="field">
          <label for="nup-layout">{{ 'pages.nup.layout' | transloco }}</label>
          <select
            id="nup-layout"
            [value]="layout()"
            [disabled]="booklet()"
            (change)="layout.set($any($event.target).value)"
          >
            <option value="2up">{{ 'pages.nup.layout2up' | transloco }}</option>
            <option value="4up">{{ 'pages.nup.layout4up' | transloco }}</option>
            <option value="6up">{{ 'pages.nup.layout6up' | transloco }}</option>
            <option value="8up">{{ 'pages.nup.layout8up' | transloco }}</option>
            <option value="9up">{{ 'pages.nup.layout9up' | transloco }}</option>
          </select>
        </div>
        <div class="field">
          <span class="field-label">{{ 'pages.nup.mode' | transloco }}</span>
          <label class="check">
            <input
              type="checkbox"
              [checked]="booklet()"
              (change)="booklet.set($any($event.target).checked)"
            />
            {{ 'pages.nup.booklet' | transloco }}
          </label>
          <span class="help">{{ 'pages.nup.bookletHelp' | transloco }}</span>
        </div>
      </div>

      <div class="btn-row">
        <button
          type="button"
          class="btn btn-primary"
          [disabled]="!files().length || state.loading()"
          (click)="submit()"
        >
          {{ 'pages.nup.submit' | transloco }}
          @if (files().length) {
            · {{ 'common.fileCount' | transloco: { count: files().length } }}
          }
        </button>
      </div>

      <app-result-panel
        [loading]="state.loading()"
        [loadingLabel]="'pages.nup.loading' | transloco"
        [error]="state.error()"
        [result]="state.result()"
        (retry)="submit()"
      />
    </section>
  `,
})
export class NupPage {
  protected readonly files = signal<File[]>([]);
  protected readonly layout = signal<NupLayout>('2up');
  protected readonly booklet = signal(false);
  protected readonly state = new OperationState();

  constructor(private readonly api: ApiService) {}

  submit(): void {
    if (!this.files().length) return;
    const fd = new FormData();
    for (const f of this.files()) fd.append('files', f, f.name);
    // Booklet imposes its own 2-up saddle-stitch order, so the grid preset is ignored server-side.
    if (this.booklet()) fd.append('booklet', 'true');
    else fd.append('layout', this.layout());
    this.state.run(this.api.nup(fd));
  }
}
