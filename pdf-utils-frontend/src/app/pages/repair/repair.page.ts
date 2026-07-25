import { Component, signal } from '@angular/core';
import { TranslocoModule } from '@jsverse/transloco';

import { ApiService } from '../../core/api.service';
import { OperationState } from '../../core/operation-state';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
import { OpProgressComponent } from '../../shared/op-progress/op-progress.component';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
import { ResultPanelComponent } from '../../shared/result-panel/result-panel.component';

/**
 * Repair a damaged PDF: the server re-parses and rebuilds the file so it opens
 * again. No options — drop files, press the button. Batch → ZIP.
 *
 * The copy stays honest on purpose: repair is a best effort, not a guarantee.
 * A single-file run reports what actually happened (rebuilt vs already fine)
 * from the `X-Repair-*` headers via `<app-result-panel>`, and a file that cannot
 * be recovered comes back as a 422 `repair_failed` with its own error copy.
 */
@Component({
  selector: 'app-repair-page',
  standalone: true,
  imports: [TranslocoModule, FileDropZoneComponent, OpProgressComponent, PageHeaderComponent, ResultPanelComponent],
  template: `
    <section class="op-page">
      <app-page-header
        [title]="'pages.repair.title' | transloco"
        [description]="'pages.repair.description' | transloco"
      />

      <app-file-drop-zone
        [multiple]="true"
        accept=".pdf"
        [hint]="'pages.repair.hint' | transloco"
        (filesChange)="files.set($event)"
      />

      <!-- The "not every file can be recovered" caveat lives in the page description
           now: as a second note here it sat under the drop zone repeating the
           description's own first sentence back at the reader. -->
      <p class="hint-note" role="note">{{ 'pages.repair.privacyLine' | transloco }}</p>
      @if (files().length > 1) {
        <p class="help">{{ 'pages.repair.batchNote' | transloco: { count: files().length } }}</p>
      }

      <div class="btn-row">
        <button
          type="button"
          class="btn btn-primary"
          [disabled]="!files().length || state.loading()"
          (click)="submit()"
        >
          {{ 'pages.repair.submit' | transloco }}
          @if (files().length) {
            · {{ 'common.fileCount' | transloco: { count: files().length } }}
          }
        </button>
        <button type="button" class="btn" (click)="clear()">{{ 'common.clear' | transloco }}</button>
      </div>

      <app-op-progress
        [run]="state.tracker()"
        [label]="'pages.repair.loading' | transloco"
        (cancel)="state.cancel()"
        (dismiss)="state.dismiss()"
      />

      <app-result-panel
        [error]="state.error()"
        [result]="state.result()"
        (retry)="submit()"
      />
    </section>
  `,
})
export class RepairPage {
  protected readonly files = signal<File[]>([]);
  protected readonly state = new OperationState();

  constructor(private readonly api: ApiService) {}

  // Nothing to persist across a refresh — the page has no settings, only files.
  clear(): void {
    this.files.set([]);
    this.state.reset();
  }

  submit(): void {
    const files = this.files();
    if (!files.length) return;
    const fd = new FormData();
    for (const f of files) fd.append('files', f, f.name);
    this.state.run(this.api.repair(fd));
  }
}
