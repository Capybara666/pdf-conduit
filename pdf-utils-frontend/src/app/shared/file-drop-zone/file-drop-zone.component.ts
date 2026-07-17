import { NgClass } from '@angular/common';
import { Component, EventEmitter, Input, Output, signal } from '@angular/core';

import { formatBytes } from '../../core/download.util';

/**
 * Reusable drag-and-drop + click file picker.
 *
 * - `multiple` toggles single vs multi selection.
 * - `accept` sets the native file-input filter (e.g. `.pdf,image/*`).
 * - Emits `filesChange` whenever the selection changes; keeps its own list so
 *   consumers can bind `[files]` for two-way-ish use or just listen.
 */
@Component({
  selector: 'app-file-drop-zone',
  standalone: true,
  imports: [NgClass],
  templateUrl: './file-drop-zone.component.html',
  styleUrl: './file-drop-zone.component.scss',
})
export class FileDropZoneComponent {
  @Input() multiple = true;
  @Input() accept = '';
  @Input() label = 'Drop files here or click to browse';
  @Input() hint = '';
  /** Show the built-in file list under the drop target. Hide it when the host
   *  renders its own list (e.g. a drag-reorder list for merge/arrange). */
  @Input() showList = true;

  @Input()
  set files(value: File[]) {
    this._files.set(value ?? []);
  }
  get files(): File[] {
    return this._files();
  }

  @Output() filesChange = new EventEmitter<File[]>();

  readonly _files = signal<File[]>([]);
  readonly dragging = signal(false);

  protected readonly formatBytes = formatBytes;

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(true);
  }

  onDragLeave(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
    const dropped = event.dataTransfer?.files;
    if (dropped && dropped.length) {
      this.addFiles(Array.from(dropped));
    }
  }

  onSelect(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length) {
      this.addFiles(Array.from(input.files));
    }
    // Reset so selecting the same file again re-fires change.
    input.value = '';
  }

  remove(index: number): void {
    const next = this._files().slice();
    next.splice(index, 1);
    this._files.set(next);
    this.filesChange.emit(next);
  }

  clear(): void {
    this._files.set([]);
    this.filesChange.emit([]);
  }

  private addFiles(incoming: File[]): void {
    if (this.multiple) {
      this._files.set([...this._files(), ...incoming]);
    } else {
      this._files.set([incoming[0]]);
    }
    this.filesChange.emit(this._files());
  }
}
