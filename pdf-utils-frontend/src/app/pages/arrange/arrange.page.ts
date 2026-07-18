import { Component, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { TranslocoModule } from '@jsverse/transloco';

import { ApiService } from '../../core/api.service';
import { OperationState } from '../../core/operation-state';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
import { PageGridComponent } from '../../shared/page-grid/page-grid.component';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
import { ResultPanelComponent } from '../../shared/result-panel/result-panel.component';

/** Reorder / reverse / duplicate the pages of one PDF via an order expression. */
@Component({
  selector: 'app-arrange-page',
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
        [title]="'pages.arrange.title' | transloco"
        [description]="'pages.arrange.description' | transloco"
      />

      <app-file-drop-zone
        [multiple]="false"
        accept=".pdf"
        [hint]="'pages.arrange.hint' | transloco"
        (filesChange)="file.set($event.length ? $event[0] : null)"
      />

      @if (file()) {
        <app-page-grid
          mode="reorder"
          [file]="file()"
          (orderStringChange)="order.setValue($event)"
        />
      }

      <div class="card form-grid">
        <div class="field full">
          <label for="ar-order">{{ 'pages.arrange.order' | transloco }}</label>
          <input
            id="ar-order"
            type="text"
            [formControl]="order"
            [placeholder]="'pages.arrange.orderPlaceholder' | transloco"
          />
          <span class="help">
            <code>3,1,2</code> {{ 'pages.arrange.orderHelp1' | transloco }} ·
            <code>5-1</code> {{ 'pages.arrange.orderHelp2' | transloco }} ·
            {{ 'pages.arrange.orderHelp3' | transloco }} (<code>1,1,2</code>).
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
          {{ 'pages.arrange.submit' | transloco }}
        </button>
      </div>

      <app-result-panel
        [loading]="state.loading()"
        [loadingLabel]="'pages.arrange.loading' | transloco"
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
