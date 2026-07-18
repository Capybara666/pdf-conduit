import { Component, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { TranslocoModule } from '@jsverse/transloco';

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
        [title]="'pages.merge.title' | transloco"
        [description]="'pages.merge.description' | transloco"
      />

      <app-file-drop-zone
        [multiple]="true"
        [showList]="false"
        accept=".pdf,image/*,.docx,.odt,.rtf,.txt,.xlsx,.pptx"
        [hint]="'pages.merge.hint' | transloco"
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
              <button
                type="button"
                class="icon-btn"
                (click)="moveUp(i)"
                [disabled]="i === 0"
                [attr.aria-label]="'common.moveUp' | transloco"
              >↑</button>
              <button
                type="button"
                class="icon-btn"
                (click)="moveDown(i)"
                [disabled]="i === files().length - 1"
                [attr.aria-label]="'common.moveDown' | transloco"
              >↓</button>
              <button
                type="button"
                class="icon-btn"
                (click)="remove(i)"
                [attr.aria-label]="'common.remove' | transloco"
              >✕</button>
            </li>
          }
        </ul>
      }

      <div class="card form-grid">
        <div class="field full">
          <label for="mg-name">{{ 'pages.merge.outputName' | transloco }}</label>
          <input
            id="mg-name"
            type="text"
            [formControl]="outputName"
            [placeholder]="'pages.merge.outputNamePlaceholder' | transloco"
          />
          <span class="help">{{ 'pages.merge.outputNameHelp' | transloco }}</span>
        </div>
      </div>

      <div class="btn-row">
        <button
          type="button"
          class="btn btn-primary"
          [disabled]="files().length < 1 || state.loading()"
          (click)="submit()"
        >
          {{ 'pages.merge.submit' | transloco: { count: files().length } }}
        </button>
      </div>

      <app-result-panel
        [loading]="state.loading()"
        [loadingLabel]="'pages.merge.loading' | transloco"
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

  /** Keyboard/touch reorder — mirrors the drag path, moving a row up one slot. */
  moveUp(i: number): void {
    if (i <= 0) return;
    this.swap(i, i - 1);
  }

  moveDown(i: number): void {
    if (i >= this.files().length - 1) return;
    this.swap(i, i + 1);
  }

  private swap(a: number, b: number): void {
    const next = this.files().slice();
    [next[a], next[b]] = [next[b], next[a]];
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
