import { Component, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';

import { ApiService } from '../../core/api.service';
import { OperationState } from '../../core/operation-state';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
import { ResultPanelComponent } from '../../shared/result-panel/result-panel.component';

/** Reorder / reverse / duplicate the pages of one PDF via an order expression. */
@Component({
  selector: 'app-arrange-page',
  standalone: true,
  imports: [ReactiveFormsModule, FileDropZoneComponent, PageHeaderComponent, ResultPanelComponent],
  template: `
    <section class="op-page">
      <app-page-header title="Arrange" description="Reorder, reverse or duplicate pages." />

      <app-file-drop-zone
        [multiple]="false"
        accept=".pdf"
        hint="One PDF."
        (filesChange)="file.set($event.length ? $event[0] : null)"
      />

      <div class="card form-grid">
        <div class="field full">
          <label for="ar-order">Page order</label>
          <input id="ar-order" type="text" [formControl]="order" placeholder="e.g. 3,1,2" />
          <span class="help">
            <code>3,1,2</code> reorders · <code>5-1</code> reverses · repeats duplicate (<code>1,1,2</code>).
          </span>
        </div>
      </div>

      <div class="btn-row">
        <button
          type="button"
          class="btn btn-primary"
          [disabled]="!file() || !order.value.trim() || state.loading()"
          (click)="submit()"
        >
          Arrange
        </button>
      </div>

      <app-result-panel
        [loading]="state.loading()"
        loadingLabel="Arranging…"
        [error]="state.error()"
        [result]="state.result()"
      />
    </section>
  `,
})
export class ArrangePage {
  protected readonly file = signal<File | null>(null);
  protected readonly order = new FormControl('', { nonNullable: true });
  protected readonly state = new OperationState();

  constructor(private readonly api: ApiService) {}

  submit(): void {
    const f = this.file();
    const order = this.order.value.trim();
    if (!f || !order) return;
    const fd = new FormData();
    fd.append('file', f, f.name);
    fd.append('order', order);
    this.state.run(this.api.arrange(fd));
  }
}
