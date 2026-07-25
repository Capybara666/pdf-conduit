import { isPlatformBrowser, NgClass } from '@angular/common';
import {
  Component,
  EventEmitter,
  Inject,
  Input,
  OnDestroy,
  OnInit,
  Output,
  PLATFORM_ID,
  signal,
} from '@angular/core';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';

import { formatBytes } from '../../core/download.util';
import { environment } from '../../../environments/environment';

/** Why a file was rejected during client-side validation. */
export type RejectionReason = 'size' | 'type' | 'count';

/** A file the drop zone refused, with the reason, emitted via `rejected`. */
export interface FileRejection {
  file: File;
  reason: RejectionReason;
}

/**
 * Reusable drag-and-drop + click file picker.
 *
 * - `multiple` toggles single vs multi selection.
 * - `accept` sets the native file-input filter (e.g. `.pdf,image/*`) and is
 *   also enforced client-side.
 * - `maxFileSizeMb` (default from `environment.maxUploadMb`) rejects oversize
 *   files before upload; `maxFiles` (default from
 *   `environment.maxFilesPerRequest`, `0` = unlimited) caps the count. Both
 *   defaults mirror the backend free tier, so the drop zone refuses exactly
 *   what the server would refuse — no earlier, no later.
 * - `pageDrop` (default off) opts into a whole-page drop overlay + clipboard
 *   paste so a host can enable it with a single prop.
 * - Emits `filesChange` whenever the accepted selection changes and `rejected`
 *   with the files that failed validation.
 *
 * Fully keyboard-operable: the drop target is a real `role="button"` that opens
 * the native picker on Enter/Space.
 */
@Component({
  selector: 'app-file-drop-zone',
  standalone: true,
  imports: [NgClass, TranslocoModule],
  templateUrl: './file-drop-zone.component.html',
  styleUrl: './file-drop-zone.component.scss',
})
export class FileDropZoneComponent implements OnInit, OnDestroy {
  @Input() multiple = true;
  @Input() accept = '';
  /** Override the drop-target label; blank falls back to the i18n default. */
  @Input() label = '';
  @Input() hint = '';
  /** Show the built-in file list under the drop target. Hide it when the host
   *  renders its own list (e.g. a drag-reorder list for merge/arrange). */
  @Input() showList = true;
  /** Reject files larger than this many MB (0 disables the size check). */
  @Input() maxFileSizeMb = environment.maxUploadMb;
  /**
   * Cap the number of accepted files. Defaults to the backend's per-request
   * free-tier limit (`environment.maxFilesPerRequest`) so a user is told up
   * front instead of waiting out a large upload for a 413. Set `0` to opt out
   * explicitly (unlimited); only meaningful with `multiple`.
   */
  @Input() maxFiles = environment.maxFilesPerRequest;
  /** Opt into a page-wide drop overlay + clipboard paste. Default off so
   *  existing pages are unaffected. */
  @Input() pageDrop = false;

  @Input()
  set files(value: File[]) {
    this._files.set(value ?? []);
  }
  get files(): File[] {
    return this._files();
  }

  @Output() filesChange = new EventEmitter<File[]>();
  @Output() rejected = new EventEmitter<FileRejection[]>();

  readonly _files = signal<File[]>([]);
  readonly dragging = signal(false);
  readonly rejections = signal<FileRejection[]>([]);
  readonly pageDragging = signal(false);
  /** Index of the list row being dragged for reorder (drives the source fade). */
  readonly dragIndex = signal<number | null>(null);
  /** Insertion index (0..files.length) where a reorder drop would land, or null. */
  readonly dropIndex = signal<number | null>(null);

  protected readonly formatBytes = formatBytes;
  private readonly isBrowser: boolean;
  private hideTimer: ReturnType<typeof setTimeout> | undefined;

  constructor(
    private readonly transloco: TranslocoService,
    @Inject(PLATFORM_ID) platformId: object,
  ) {
    this.isBrowser = isPlatformBrowser(platformId);
  }

  ngOnInit(): void {
    if (!this.pageDrop || !this.isBrowser) {
      return;
    }
    window.addEventListener('dragover', this.onWindowDragOver);
    window.addEventListener('dragleave', this.onWindowDragLeave);
    window.addEventListener('drop', this.onWindowDrop);
    window.addEventListener('dragend', this.onWindowDragEnd);
    window.addEventListener('paste', this.onWindowPaste);
  }

  ngOnDestroy(): void {
    if (!this.pageDrop || !this.isBrowser) {
      return;
    }
    clearTimeout(this.hideTimer);
    window.removeEventListener('dragover', this.onWindowDragOver);
    window.removeEventListener('dragleave', this.onWindowDragLeave);
    window.removeEventListener('drop', this.onWindowDrop);
    window.removeEventListener('dragend', this.onWindowDragEnd);
    window.removeEventListener('paste', this.onWindowPaste);
  }

  /** Localized constraint helper text, e.g. "PDF · up to 20 MB". */
  get constraintsText(): string {
    const parts: string[] = [];
    const types = this.acceptSummary();
    if (types) {
      parts.push(types);
    }
    if (this.maxFileSizeMb > 0) {
      parts.push(this.transloco.translate('dropzone.upToSize', { size: this.maxFileSizeMb }));
    }
    if (this.multiple && this.maxFiles > 0) {
      parts.push(this.transloco.translate('dropzone.maxFiles', { count: this.maxFiles }));
    }
    return parts.join(' · ');
  }

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

  // --- Drag-to-reorder for the built-in file list (showList) -----------------

  onRowDragStart(event: DragEvent, index: number): void {
    this.dragIndex.set(index);
    this.dropIndex.set(index);
    // Mark this as a move (not a copy) so the cursor/UX reads as reorder, and
    // populate dataTransfer so Firefox actually starts the drag.
    if (event.dataTransfer) {
      event.dataTransfer.effectAllowed = 'move';
      event.dataTransfer.setData('text/plain', String(index));
    }
  }

  /**
   * Move the insertion marker (not the list) while dragging: the cursor in the
   * top half of a row inserts BEFORE it, the bottom half AFTER it — so the
   * marker can also land above the first row (index 0) and below the last
   * (index N). The list is only committed on drop.
   */
  onRowDragOver(event: DragEvent, over: number): void {
    if (this.dragIndex() === null) {
      return;
    }
    // Must preventDefault on every dragover so this row is a valid drop target
    // and the subsequent `drop` fires (a missing one is why drops silently die).
    event.preventDefault();
    if (event.dataTransfer) {
      event.dataTransfer.dropEffect = 'move';
    }
    const el = event.currentTarget as HTMLElement;
    const rect = el.getBoundingClientRect();
    const leading = event.clientY < rect.top + rect.height / 2;
    this.dropIndex.set(leading ? over : over + 1);
  }

  /**
   * Container-level dragover: preventDefault so a release ANYWHERE inside the
   * list (a gap, the thin insertion marker) is a valid drop and the following
   * `drop` fires. It never moves the marker — the per-row dragover owns that;
   * this only keeps the drop alive when the pointer isn't over a row.
   */
  onListDragOver(event: DragEvent): void {
    if (this.dragIndex() === null) {
      return;
    }
    event.preventDefault();
    if (event.dataTransfer) {
      event.dataTransfer.dropEffect = 'move';
    }
  }

  onRowDrop(event: DragEvent): void {
    if (this.dragIndex() === null) {
      return;
    }
    event.preventDefault();
    const from = this.dragIndex();
    const to = this.dropIndex();
    if (from !== null && to !== null) {
      const next = this._files().slice();
      const [moved] = next.splice(from, 1);
      // Same-list off-by-one: removing `from` shifts everything after it down by
      // one, so a marker at `to` past the source maps to `to - 1`.
      const target = to > from ? to - 1 : to;
      if (target !== from) {
        next.splice(target, 0, moved);
        this._files.set(next);
        this.filesChange.emit(next);
      }
    }
    this.onRowDragEnd();
  }

  onRowDragEnd(): void {
    this.dragIndex.set(null);
    this.dropIndex.set(null);
  }

  /** Dismiss the inline rejection message. */
  dismissRejections(): void {
    this.rejections.set([]);
  }

  private addFiles(incoming: File[]): void {
    const accepted: File[] = [];
    const rejected: FileRejection[] = [];
    const existing = this.multiple ? this._files().length : 0;

    for (const file of incoming) {
      if (!this.matchesAccept(file)) {
        rejected.push({ file, reason: 'type' });
        continue;
      }
      if (this.maxFileSizeMb > 0 && file.size > this.maxFileSizeMb * 1024 * 1024) {
        rejected.push({ file, reason: 'size' });
        continue;
      }
      if (this.multiple && this.maxFiles > 0 && existing + accepted.length >= this.maxFiles) {
        rejected.push({ file, reason: 'count' });
        continue;
      }
      accepted.push(file);
    }

    this.rejections.set(rejected);
    if (rejected.length) {
      this.rejected.emit(rejected);
    }
    if (!accepted.length) {
      return;
    }

    if (this.multiple) {
      this._files.set([...this._files(), ...accepted]);
    } else {
      this._files.set([accepted[0]]);
    }
    this.filesChange.emit(this._files());
  }

  /** True when `file` matches the `accept` filter (empty accept = anything). */
  private matchesAccept(file: File): boolean {
    const accept = this.accept.trim();
    if (!accept) {
      return true;
    }
    const name = file.name.toLowerCase();
    const type = (file.type || '').toLowerCase();
    return accept
      .split(',')
      .map((token) => token.trim().toLowerCase())
      .filter(Boolean)
      .some((token) => {
        if (token.startsWith('.')) {
          return name.endsWith(token);
        }
        if (token.endsWith('/*')) {
          return type.startsWith(token.slice(0, -1));
        }
        return type === token;
      });
  }

  /** Human, localized summary of the accepted types for the helper text. */
  private acceptSummary(): string {
    const accept = this.accept.trim();
    if (!accept) {
      return this.transloco.translate('dropzone.types.any');
    }
    const tokens = accept
      .split(',')
      .map((token) => token.trim().toLowerCase())
      .filter(Boolean);
    const officeExts = ['.docx', '.odt', '.rtf', '.txt', '.xlsx', '.pptx', '.doc', '.ppt', '.xls'];
    const imageExts = ['.png', '.jpg', '.jpeg', '.gif', '.webp', '.bmp', '.tif', '.tiff'];
    const markupExts = ['.md', '.markdown', '.html', '.htm'];
    const keys: string[] = [];
    if (tokens.includes('.pdf') || tokens.includes('application/pdf')) {
      keys.push('pdf');
    }
    if (tokens.includes('image/*') || tokens.some((t) => imageExts.includes(t))) {
      keys.push('images');
    }
    if (tokens.some((t) => officeExts.includes(t))) {
      keys.push('office');
    }
    if (tokens.some((t) => markupExts.includes(t))) {
      keys.push('markup');
    }
    if (tokens.includes('.json') || tokens.includes('application/json')) {
      keys.push('json');
    }
    const chosen = keys.length ? keys : ['any'];
    return chosen.map((key) => this.transloco.translate('dropzone.types.' + key)).join(', ');
  }

  // --- Page-wide drop overlay + clipboard paste (gated by `pageDrop`) -------

  private hasFiles(event: DragEvent): boolean {
    const types = event.dataTransfer?.types;
    return !!types && Array.from(types).includes('Files');
  }

  private readonly onWindowDragOver = (event: DragEvent): void => {
    if (!this.hasFiles(event)) {
      return;
    }
    event.preventDefault();
    this.pageDragging.set(true);
    clearTimeout(this.hideTimer);
    // dragover fires continuously during a drag; if it stops we assume the
    // pointer left the window and hide the overlay.
    this.hideTimer = setTimeout(() => this.pageDragging.set(false), 150);
  };

  private readonly onWindowDragLeave = (event: DragEvent): void => {
    if (event.relatedTarget === null) {
      this.pageDragging.set(false);
    }
  };

  private readonly onWindowDragEnd = (): void => {
    clearTimeout(this.hideTimer);
    this.pageDragging.set(false);
  };

  private readonly onWindowDrop = (event: DragEvent): void => {
    if (!this.hasFiles(event)) {
      return;
    }
    event.preventDefault();
    clearTimeout(this.hideTimer);
    this.pageDragging.set(false);
    const files = event.dataTransfer?.files;
    if (files && files.length) {
      this.addFiles(Array.from(files));
    }
  };

  private readonly onWindowPaste = (event: ClipboardEvent): void => {
    // Don't hijack paste while the user is editing a text field.
    const active = this.isBrowser ? document.activeElement : null;
    if (active) {
      const tag = active.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || (active as HTMLElement).isContentEditable) {
        return;
      }
    }
    const data = event.clipboardData;
    if (!data) {
      return;
    }
    const files: File[] = [];
    if (data.files && data.files.length) {
      files.push(...Array.from(data.files));
    } else {
      for (const item of Array.from(data.items || [])) {
        if (item.kind === 'file') {
          const file = item.getAsFile();
          if (file) {
            files.push(file);
          }
        }
      }
    }
    if (files.length) {
      event.preventDefault();
      this.addFiles(files);
    }
  };
}
