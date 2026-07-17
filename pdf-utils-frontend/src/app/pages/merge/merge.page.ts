import { Component, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';

import { ApiService } from '../../core/api.service';
import { OperationState } from '../../core/operation-state';
import { formatBytes } from '../../core/download.util';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
import { ResultPanelComponent } from '../../shared/result-panel/result-panel.component';

/** Merge several files (PDF/image/office) into one PDF, with drag-reorder. */
@Component({
  selector: 'app-merge-page',
  standalone: true,
  imports: [ReactiveFormsModule, FileDropZoneComponent, PageHeaderComponent, ResultPanelComponent],
  template: `
    <section class="op-page">
      <app-page-header
        title="Merge"
        description="Combine several PDFs, images or office documents into one PDF."
      />

      <app-file-drop-zone
        [multiple]="true"
        [showList]="false"
        accept=".pdf,image/*,.docx,.odt,.rtf,.txt,.xlsx,.pptx"
        hint="PDFs, images and office docs. Drag the list below to set the order."
        (filesChange)="onFiles($event)"
      />

      @if (files().length) {
        <ul class="reorder-list">
          @for (f of files(); track f; let i = $index) {
            <li
              [class.dragging]="dragIndex() === i"
              draggable="true"
              (dragstart)="onDragStart(i)"
              (dragover)="onDragOver($event, i)"
              (dragend)="dragIndex.set(null)"
            >
              <span class="grip" aria-hidden="true">⠿</span>
              <span class="idx">{{ i + 1 }}</span>
              <span class="name" [title]="f.name">{{ f.name }}</span>
              <span class="size">{{ formatBytes(f.size) }}</span>
              <button type="button" class="icon-btn" (click)="remove(i)" aria-label="Remove">✕</button>
            </li>
          }
        </ul>
      }

      <div class="card form-grid">
        <div class="field full">
          <label for="mg-name">Output name (optional)</label>
          <input id="mg-name" type="text" [formControl]="outputName" placeholder="merged.pdf" />
          <span class="help">Defaults to the first file's name with a “_merged” suffix.</span>
        </div>
      </div>

      <div class="btn-row">
        <button
          type="button"
          class="btn btn-primary"
          [disabled]="files().length < 1 || state.loading()"
          (click)="submit()"
        >
          Merge {{ files().length }} file{{ files().length === 1 ? '' : 's' }}
        </button>
      </div>

      <app-result-panel
        [loading]="state.loading()"
        loadingLabel="Merging…"
        [error]="state.error()"
        [result]="state.result()"
      />
    </section>
  `,
})
export class MergePage {
  protected readonly files = signal<File[]>([]);
  protected readonly dragIndex = signal<number | null>(null);
  protected readonly outputName = new FormControl('', { nonNullable: true });
  protected readonly state = new OperationState();
  protected readonly formatBytes = formatBytes;

  constructor(private readonly api: ApiService) {}

  onFiles(files: File[]): void {
    this.files.set(files);
  }

  remove(i: number): void {
    const next = this.files().slice();
    next.splice(i, 1);
    this.files.set(next);
  }

  onDragStart(i: number): void {
    this.dragIndex.set(i);
  }

  onDragOver(ev: DragEvent, over: number): void {
    ev.preventDefault();
    const from = this.dragIndex();
    if (from === null || from === over) return;
    const next = this.files().slice();
    const [moved] = next.splice(from, 1);
    next.splice(over, 0, moved);
    this.files.set(next);
    this.dragIndex.set(over);
  }

  submit(): void {
    const fd = new FormData();
    for (const f of this.files()) fd.append('files', f, f.name);
    const name = this.outputName.value.trim();
    if (name) fd.append('outputName', name);
    this.state.run(this.api.merge(fd));
  }
}
