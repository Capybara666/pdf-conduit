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

      @if (files().length > 1) {
        <p class="hint-note" role="note">{{ 'pages.merge.reorderHint' | transloco }}</p>
      }
      @if (files().length) {
        <ul
          class="reorder-list"
          (dragover)="onContainerDragOver($event)"
          (drop)="onDrop($event)"
        >
          @for (f of files(); track f; let i = $index) {
            @if (dragIndex() !== null && dropIndex() === i) {
              <li class="drop-marker" aria-hidden="true"></li>
            }
            <li
              [class.dragging]="dragIndex() === i"
              draggable="true"
              (dragstart)="onDragStart($event, i)"
              (dragover)="onDragOver($event, i)"
              (drop)="onDrop($event)"
              (dragend)="onDragEnd()"
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
          @if (dragIndex() !== null && dropIndex() === files().length) {
            <li class="drop-marker" aria-hidden="true"></li>
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
          {{ 'pages.merge.submit' | transloco }}
          @if (files().length) {
            · {{ 'common.fileCount' | transloco: { count: files().length } }}
          }
        </button>
      </div>

      <app-result-panel
        [loading]="state.loading()"
        [loadingLabel]="'pages.merge.loading' | transloco"
        [error]="state.error()"
        [result]="state.result()"
        (retry)="submit()"
      />
    </section>
  `,
  styles: [
    `
      /* Slim insertion marker: a horizontal bar sitting in the gap where the
         dragged row will land. Overrides the global .reorder-list li chrome so
         it reads as a thin accent bar, not a full tile. */
      .reorder-list li.drop-marker {
        height: 3px;
        min-height: 0;
        padding: 0;
        margin: 0;
        border: 0;
        border-radius: 2px;
        background: var(--accent);
        box-shadow: 0 0 0 1px var(--accent-soft);
        /* Let the cursor pass through the thin marker to the row/container
           beneath, so releasing on it is still a valid drop (not a no-op). */
        pointer-events: none;
      }
    `,
  ],
})
export class MergePage {
  protected readonly files = signal<File[]>([]);
  /** Index of the row currently being dragged (drives the source-row fade). */
  protected readonly dragIndex = signal<number | null>(null);
  /** Insertion index (0..files().length) where a drop would land, or null. */
  protected readonly dropIndex = signal<number | null>(null);
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

  onDragStart(ev: DragEvent, i: number): void {
    this.dragIndex.set(i);
    // Populate dataTransfer so Firefox reliably initiates the drag, and flag it
    // as a move (reorder), not a copy.
    if (ev.dataTransfer) {
      ev.dataTransfer.effectAllowed = 'move';
      ev.dataTransfer.setData('text/plain', String(i));
    }
  }

  /**
   * Container-level dragover: preventDefault so a release ANYWHERE inside the
   * list (a gap, the thin insertion marker) is a valid drop and the following
   * `drop` fires. It never moves the marker — the per-row dragover owns that;
   * this only keeps the drop alive when the pointer isn't over a row.
   */
  onContainerDragOver(ev: DragEvent): void {
    if (this.dragIndex() === null) return;
    ev.preventDefault();
  }

  /**
   * Move the insertion marker (not the list) while dragging: the cursor in the
   * top half of a row inserts BEFORE it, the bottom half AFTER it — so the
   * marker can also land above the first row (index 0) and below the last
   * (index N). The list is never mutated here; it only commits on drop.
   */
  onDragOver(ev: DragEvent, over: number): void {
    ev.preventDefault();
    if (this.dragIndex() === null) return;
    const el = ev.currentTarget as HTMLElement;
    const rect = el.getBoundingClientRect();
    const leading = ev.clientY < rect.top + rect.height / 2;
    this.dropIndex.set(leading ? over : over + 1);
  }

  onDrop(ev: DragEvent): void {
    ev.preventDefault();
    const from = this.dragIndex();
    const to = this.dropIndex();
    if (from !== null && to !== null) {
      const next = this.files().slice();
      const [moved] = next.splice(from, 1);
      // Same-list off-by-one: removing `from` shifts everything after it down by
      // one, so a marker at `to` past the source maps to `to - 1`.
      const target = to > from ? to - 1 : to;
      next.splice(target, 0, moved);
      this.files.set(next);
    }
    this.onDragEnd();
  }

  onDragEnd(): void {
    this.dragIndex.set(null);
    this.dropIndex.set(null);
  }

  submit(): void {
    const fd = new FormData();
    for (const f of this.files()) fd.append('files', f, f.name);
    const name = this.outputName.value.trim();
    if (name) fd.append('outputName', name);
    this.state.run(this.api.merge(fd));
  }
}
