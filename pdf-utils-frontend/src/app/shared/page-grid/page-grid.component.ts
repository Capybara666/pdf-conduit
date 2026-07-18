import {
  Component,
  ElementRef,
  EventEmitter,
  Input,
  NgZone,
  OnDestroy,
  Output,
  inject,
  signal,
} from '@angular/core';

import { TranslocoModule, TranslocoService } from '@jsverse/transloco';

import { loadPdf, PDFDocumentProxy } from '../../core/pdfjs';
import { SpinnerComponent } from '../spinner/spinner.component';
import { toCompactRange, toOrderString } from './page-range.util';

/** A tile in reorder mode: a stable id (survives reorder/duplicate) + source page. */
interface OrderTile {
  id: number;
  page: number; // 1-based source page
}

/**
 * Reusable pdf.js thumbnail grid — the web counterpart of the desktop's visual
 * page-select / page-arrange grids.
 *
 * Two modes:
 * - `select`  — every page starts selected; clicking a tile toggles it. Emits the
 *   sorted page list and the compact range string the backend understands
 *   (`toCompactRange`; all-selected ⇒ `''`).
 * - `reorder` — tiles start in document order and can be dragged, duplicated or
 *   removed. Emits the visual page sequence and its comma-joined order string.
 *
 * Each page is rendered to a `<canvas>` exactly once (cached; duplicates blit the
 * same source), and tiles paint lazily via an IntersectionObserver so a large PDF
 * does not rasterise every page up front. Selection / reordering only mutate the
 * model + CSS — they never re-render a thumbnail.
 */
@Component({
  selector: 'app-page-grid',
  standalone: true,
  imports: [SpinnerComponent, TranslocoModule],
  template: `
    @if (loading()) {
      <div class="pg-loading"><app-spinner [label]="'pageGrid.loading' | transloco" /></div>
    }
    @if (errorMsg()) {
      <p class="pg-error" role="alert">{{ errorMsg() }}</p>
    }

    @if (ready() && !tooMany()) {
      <div class="pg-toolbar" role="toolbar" [attr.aria-label]="'pageGrid.toolbar' | transloco">
        @if (mode === 'select') {
          <button type="button" class="pg-tool" (click)="selectAll()">{{ 'pageGrid.selectAll' | transloco }}</button>
          <button type="button" class="pg-tool" (click)="selectNone()">{{ 'pageGrid.none' | transloco }}</button>
          <button type="button" class="pg-tool" (click)="invert()">{{ 'pageGrid.invert' | transloco }}</button>
          <button type="button" class="pg-tool" (click)="odd()">{{ 'pageGrid.odd' | transloco }}</button>
          <button type="button" class="pg-tool" (click)="even()">{{ 'pageGrid.even' | transloco }}</button>
          <span class="pg-count" aria-live="polite">
            {{ 'pageGrid.ofCount' | transloco: { n: selectedCount(), m: total() } }}
          </span>
        } @else {
          <button type="button" class="pg-tool" (click)="reset()">{{ 'pageGrid.reset' | transloco }}</button>
          <button type="button" class="pg-tool" (click)="reverse()">{{ 'pageGrid.reverse' | transloco }}</button>
          <span class="pg-count" aria-live="polite">
            {{ 'pageGrid.count' | transloco: { n: order().length } }}
          </span>
        }
      </div>

      <div class="pg-grid" #scroll [style.--thumb.px]="thumbWidth">
        @if (mode === 'reorder' && dropMarker(); as m) {
          <div
            class="pg-drop-marker"
            aria-hidden="true"
            [style.left.px]="m.left"
            [style.top.px]="m.top"
            [style.height.px]="m.height"
          ></div>
        }
        @if (mode === 'select') {
          @for (n of pageList(); track n) {
            <button
              type="button"
              class="pg-tile"
              [class.selected]="isSelected(n)"
              [attr.data-tile]="n"
              [attr.data-page]="n"
              [attr.aria-pressed]="isSelected(n)"
              [attr.aria-label]="'pageGrid.pageLabel' | transloco: { n: n }"
              [style.width.px]="thumbWidth"
              (click)="toggle(n)"
            >
              <span class="pg-thumb" [style.height.px]="thumbHeight(n)">
                <canvas class="thumb"></canvas>
              </span>
              <span class="pg-badge" aria-hidden="true">{{ isSelected(n) ? '✓' : '' }}</span>
              <span class="pg-caption">{{ 'pageGrid.pageLabel' | transloco: { n: n } }}</span>
            </button>
          }
        } @else {
          @for (t of order(); track t.id; let i = $index) {
            <div
              class="pg-tile reorder"
              [class.dragging]="dragIndex() === i"
              [attr.data-tile]="t.id"
              [attr.data-page]="t.page"
              [style.width.px]="thumbWidth"
              draggable="true"
              [attr.aria-label]="'pageGrid.pageLabel' | transloco: { n: t.page }"
              (dragstart)="onDragStart(i)"
              (dragover)="onDragOver($event, i)"
              (drop)="onDrop()"
              (dragend)="onDragEnd()"
            >
              <span class="pg-thumb" [style.height.px]="thumbHeight(t.page)">
                <canvas class="thumb"></canvas>
              </span>
              <span class="pg-caption">{{ 'pageGrid.pageLabel' | transloco: { n: t.page } }}</span>
              <div class="pg-actions">
                <button
                  type="button"
                  class="pg-act"
                  (click)="moveLeft(i)"
                  [disabled]="i === 0"
                  [attr.aria-label]="'pageGrid.moveLeft' | transloco"
                >‹</button>
                <button
                  type="button"
                  class="pg-act"
                  (click)="duplicate(i)"
                  [attr.aria-label]="'pageGrid.duplicate' | transloco"
                  [attr.title]="'pageGrid.duplicate' | transloco"
                >⧉</button>
                <button
                  type="button"
                  class="pg-act danger"
                  (click)="remove(i)"
                  [disabled]="order().length <= 1"
                  [attr.aria-label]="'pageGrid.remove' | transloco"
                  [attr.title]="'pageGrid.remove' | transloco"
                >✕</button>
                <button
                  type="button"
                  class="pg-act"
                  (click)="moveRight(i)"
                  [disabled]="i === order().length - 1"
                  [attr.aria-label]="'pageGrid.moveRight' | transloco"
                >›</button>
              </div>
            </div>
          }
        }
      </div>
    }
  `,
  styles: [
    `
      :host {
        display: block;
      }
      .pg-loading {
        padding: 0.75rem 0;
      }
      .pg-error {
        color: var(--danger);
        font-size: 0.9rem;
        margin: 0.25rem 0;
      }
      .pg-toolbar {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 0.4rem;
        margin-bottom: 0.6rem;
      }
      .pg-tool {
        border: 1px solid var(--border-strong);
        background: var(--surface);
        color: var(--text);
        border-radius: calc(var(--radius) - 4px);
        padding: 0.3rem 0.65rem;
        font-size: 0.82rem;
        cursor: pointer;
        transition: border-color 0.12s ease, background 0.12s ease;
      }
      .pg-tool:hover {
        border-color: var(--accent);
        background: var(--accent-soft);
      }
      .pg-count {
        margin-left: auto;
        font-size: 0.82rem;
        color: var(--text-muted);
        font-variant-numeric: tabular-nums;
      }
      .pg-grid {
        position: relative;
        display: flex;
        flex-wrap: wrap;
        gap: 0.75rem;
        max-height: 460px;
        overflow-y: auto;
        padding: 0.25rem;
      }
      /* Slim insertion marker: sits in the gap between two tiles, matching the
         hovered tile's row/height, so the drop position is unambiguous. */
      .pg-drop-marker {
        position: absolute;
        width: 3px;
        border-radius: 2px;
        background: var(--accent);
        box-shadow: 0 0 0 1px var(--accent-soft);
        pointer-events: none;
        z-index: 5;
      }
      .pg-tile {
        position: relative;
        display: flex;
        flex-direction: column;
        align-items: stretch;
        gap: 0.3rem;
        padding: 0;
        border: 2px solid var(--border);
        border-radius: calc(var(--radius) - 4px);
        background: var(--surface);
        color: var(--text);
        cursor: pointer;
        overflow: hidden;
        font: inherit;
        text-align: center;
        transition: border-color 0.12s ease, box-shadow 0.12s ease, opacity 0.12s ease;
      }
      .pg-tile:hover {
        border-color: var(--border-strong);
      }
      .pg-tile.selected {
        border-color: var(--accent);
        box-shadow: 0 0 0 1px var(--accent);
      }
      .pg-tile:focus-visible {
        outline: 2px solid var(--accent);
        outline-offset: 2px;
      }
      .pg-tile.reorder {
        cursor: grab;
      }
      .pg-tile.reorder.dragging {
        opacity: 0.5;
      }
      .pg-thumb {
        display: flex;
        align-items: center;
        justify-content: center;
        background: #fff;
        border-bottom: 1px solid var(--border);
      }
      canvas.thumb {
        display: block;
        max-width: 100%;
        height: auto;
      }
      .pg-badge {
        position: absolute;
        top: 4px;
        right: 4px;
        min-width: 18px;
        height: 18px;
        line-height: 18px;
        font-size: 0.72rem;
        color: var(--accent-contrast);
        border-radius: 50%;
        pointer-events: none;
      }
      .pg-tile.selected .pg-badge {
        background: var(--accent);
      }
      .pg-caption {
        font-size: 0.72rem;
        color: var(--text-muted);
        padding: 0 0.25rem 0.35rem;
      }
      .pg-actions {
        display: flex;
        justify-content: center;
        gap: 0.15rem;
        padding: 0 0.15rem 0.3rem;
      }
      .pg-act {
        flex: 1;
        border: 1px solid var(--border);
        background: var(--surface-2);
        color: var(--text-muted);
        border-radius: 4px;
        padding: 0.15rem 0;
        font-size: 0.8rem;
        line-height: 1;
        cursor: pointer;
      }
      .pg-act:hover:not(:disabled) {
        border-color: var(--accent);
        color: var(--text);
      }
      .pg-act.danger:hover:not(:disabled) {
        border-color: var(--danger);
        color: var(--danger);
      }
      .pg-act:disabled {
        opacity: 0.4;
        cursor: default;
      }
    `,
  ],
})
export class PageGridComponent implements OnDestroy {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly zone = inject(NgZone);
  private readonly transloco = inject(TranslocoService);

  @Input() mode: 'select' | 'reorder' = 'select';
  @Input() thumbWidth = 120;
  @Input() maxPages = 300;

  /** Optional compact-range seed for select mode (e.g. `"1,3,5-8"`). */
  @Input()
  set range(value: string | null | undefined) {
    this._seed = value ?? '';
    if (this.ready() && this.mode === 'select') this.applySeed();
  }

  @Input()
  set file(value: File | Blob | null | undefined) {
    if (value === this._file) return;
    this._file = value ?? null;
    void this.reload();
  }

  @Output() selectionChange = new EventEmitter<number[]>();
  @Output() rangeChange = new EventEmitter<string>();
  @Output() orderChange = new EventEmitter<number[]>();
  @Output() orderStringChange = new EventEmitter<string>();
  @Output() loaded = new EventEmitter<{ pages: number }>();
  @Output() renderError = new EventEmitter<string>();

  readonly loading = signal(false);
  readonly ready = signal(false);
  readonly tooMany = signal(false);
  readonly errorMsg = signal<string | null>(null);
  readonly total = signal(0);
  readonly pageList = signal<number[]>([]);
  readonly selectedCount = signal(0);
  readonly order = signal<OrderTile[]>([]);
  /** Index of the tile currently being dragged (drives the source-tile fade). */
  readonly dragIndex = signal<number | null>(null);
  /** Absolute placement of the insertion marker within the grid, or null. */
  readonly dropMarker = signal<{ left: number; top: number; height: number } | null>(null);

  private _file: File | Blob | null = null;
  private _seed = '';
  private doc: PDFDocumentProxy | null = null;
  private renderToken = 0;
  private selected = new Set<number>();
  private nextTileId = 1;
  /** Insertion index (0..order().length) where a drop would land. */
  private dropTargetIndex: number | null = null;

  // Per-page aspect (points) for stable placeholder sizing, cached rendered
  // source canvases, painted tile ids, and the lazy-render observer.
  private pageDims = new Map<number, { w: number; h: number }>();
  private sourceCanvas = new Map<number, HTMLCanvasElement>();
  private painted = new Set<number>();
  private observer: IntersectionObserver | null = null;
  private observed = new WeakSet<Element>();

  ngOnDestroy(): void {
    this.observer?.disconnect();
    this.sourceCanvas.clear();
    void this.doc?.destroy();
  }

  // ---- selection (select mode) ------------------------------------------

  isSelected(page: number): boolean {
    return this.selected.has(page);
  }

  toggle(page: number): void {
    if (this.selected.has(page)) this.selected.delete(page);
    else this.selected.add(page);
    this.afterSelectionChange();
  }

  selectAll(): void {
    this.selected = new Set(this.pageList());
    this.afterSelectionChange();
  }

  selectNone(): void {
    this.selected = new Set();
    this.afterSelectionChange();
  }

  invert(): void {
    this.selected = new Set(this.pageList().filter((n) => !this.selected.has(n)));
    this.afterSelectionChange();
  }

  odd(): void {
    this.selected = new Set(this.pageList().filter((n) => n % 2 === 1));
    this.afterSelectionChange();
  }

  even(): void {
    this.selected = new Set(this.pageList().filter((n) => n % 2 === 0));
    this.afterSelectionChange();
  }

  private afterSelectionChange(): void {
    this.selected = new Set(this.selected);
    this.selectedCount.set(this.selected.size);
    const arr = [...this.selected].sort((a, b) => a - b);
    this.selectionChange.emit(arr);
    this.rangeChange.emit(toCompactRange(arr, this.total()));
  }

  private applySeed(): void {
    const parsed = this.parseSeed(this._seed, this.total());
    this.selected = parsed ?? new Set(this.pageList());
    this.selectedCount.set(this.selected.size);
    // Do not emit on seed — the host owns the source value.
  }

  /** Parse a compact numeric range (`1,3,5-8`) into a page set; null if blank/invalid. */
  private parseSeed(text: string, total: number): Set<number> | null {
    const trimmed = (text ?? '').trim();
    if (!trimmed) return null;
    const out = new Set<number>();
    for (const raw of trimmed.split(',')) {
      const token = raw.trim();
      if (!token) continue;
      const m = /^(\d+)(?:-(\d+))?$/.exec(token);
      if (!m) return null; // 'end-2' etc. — let the text field own it
      const a = +m[1];
      const b = m[2] ? +m[2] : a;
      const lo = Math.min(a, b);
      const hi = Math.max(a, b);
      for (let n = lo; n <= hi; n++) if (n >= 1 && n <= total) out.add(n);
    }
    return out.size ? out : null;
  }

  // ---- ordering (reorder mode) ------------------------------------------

  reset(): void {
    this.order.set(this.pageList().map((page) => ({ id: this.nextTileId++, page })));
    this.afterOrderChange();
  }

  reverse(): void {
    this.order.set([...this.order()].reverse());
    this.afterOrderChange();
  }

  duplicate(index: number): void {
    const arr = this.order().slice();
    arr.splice(index + 1, 0, { id: this.nextTileId++, page: arr[index].page });
    this.order.set(arr);
    this.afterOrderChange();
  }

  remove(index: number): void {
    if (this.order().length <= 1) return;
    const arr = this.order().slice();
    arr.splice(index, 1);
    this.order.set(arr);
    this.afterOrderChange();
  }

  moveLeft(index: number): void {
    if (index <= 0) return;
    this.moveTile(index, index - 1);
  }

  moveRight(index: number): void {
    if (index >= this.order().length - 1) return;
    this.moveTile(index, index + 1);
  }

  onDragStart(index: number): void {
    this.dragIndex.set(index);
  }

  /**
   * Compute the insertion index + marker placement from the cursor position.
   * Leading half of a tile inserts BEFORE it, trailing half AFTER it; the marker
   * is anchored to the hovered tile's rect so it always renders on the cursor's
   * own row (end-of-row vs start-of-next-row are disambiguated for free, since
   * the marker follows whichever tile is under the cursor).
   */
  onDragOver(event: DragEvent, index: number): void {
    event.preventDefault();
    const el = event.currentTarget as HTMLElement;
    const rect = el.getBoundingClientRect();
    const leading = event.clientX < rect.left + rect.width / 2;
    this.dropTargetIndex = leading ? index : index + 1;

    const gap = 12; // matches the .pg-grid flex gap (0.75rem)
    const markerW = 3;
    const grid = el.offsetParent as HTMLElement | null;
    const maxLeft = grid ? grid.clientWidth - markerW - 1 : Number.MAX_SAFE_INTEGER;
    let left = leading
      ? el.offsetLeft - gap / 2 - markerW / 2
      : el.offsetLeft + el.offsetWidth + gap / 2 - markerW / 2;
    left = Math.max(1, Math.min(left, maxLeft));
    this.dropMarker.set({ left, top: el.offsetTop, height: el.offsetHeight });
  }

  onDrop(): void {
    const from = this.dragIndex();
    const to = this.dropTargetIndex;
    if (from !== null && to !== null) {
      const arr = this.order().slice();
      const [moved] = arr.splice(from, 1);
      // Same-list off-by-one: removing `from` shifts everything after it down by
      // one, so a marker at `to` past the source maps to `to - 1`.
      const target = to > from ? to - 1 : to;
      arr.splice(target, 0, moved);
      this.order.set(arr);
      this.afterOrderChange();
    }
    this.onDragEnd();
  }

  onDragEnd(): void {
    this.dragIndex.set(null);
    this.dropTargetIndex = null;
    this.dropMarker.set(null);
  }

  private moveTile(from: number, to: number): void {
    const arr = this.order().slice();
    const [moved] = arr.splice(from, 1);
    arr.splice(to, 0, moved);
    this.order.set(arr);
    this.afterOrderChange();
  }

  private afterOrderChange(): void {
    const pages = this.order().map((t) => t.page);
    this.orderChange.emit(pages);
    this.orderStringChange.emit(toOrderString(pages));
    this.scheduleObserve();
  }

  thumbHeight(page: number): number {
    const d = this.pageDims.get(page);
    if (!d || d.w === 0) return Math.round(this.thumbWidth * 1.294); // ~US-letter fallback
    return Math.round(this.thumbWidth * (d.h / d.w));
  }

  // ---- load + lazy render -----------------------------------------------

  private async reload(): Promise<void> {
    const token = ++this.renderToken;
    this.observer?.disconnect();
    this.observer = null;
    this.observed = new WeakSet();
    this.painted.clear();
    this.sourceCanvas.clear();
    this.pageDims.clear();
    this.ready.set(false);
    this.tooMany.set(false);
    this.errorMsg.set(null);
    this.pageList.set([]);
    this.order.set([]);
    void this.doc?.destroy();
    this.doc = null;
    if (!this._file) return;

    this.loading.set(true);
    try {
      const buf = await this._file.arrayBuffer();
      const doc = await loadPdf(buf);
      if (token !== this.renderToken) {
        void doc.destroy();
        return;
      }
      this.doc = doc;
      const pages = doc.numPages;
      this.total.set(pages);
      this.loaded.emit({ pages });

      if (pages > this.maxPages) {
        this.tooMany.set(true);
        const msg = this.transloco.translate('pageGrid.tooManyPages', { max: this.maxPages, pages });
        this.errorMsg.set(msg);
        this.renderError.emit(msg);
        return;
      }

      // Cheap metadata pass: page aspect ratios for stable placeholder sizing.
      for (let n = 1; n <= pages; n++) {
        if (token !== this.renderToken) return;
        const page = await doc.getPage(n);
        const vp = page.getViewport({ scale: 1 });
        this.pageDims.set(n, { w: vp.width, h: vp.height });
      }
      if (token !== this.renderToken) return;

      this.pageList.set(Array.from({ length: pages }, (_, i) => i + 1));
      this.ready.set(true);

      if (this.mode === 'select') {
        const seed = this.parseSeed(this._seed, pages);
        this.selected = seed ?? new Set(this.pageList());
        this.selectedCount.set(this.selected.size);
        const arr = [...this.selected].sort((a, b) => a - b);
        this.selectionChange.emit(arr);
        this.rangeChange.emit(toCompactRange(arr, pages));
      } else {
        this.order.set(this.pageList().map((p) => ({ id: this.nextTileId++, page: p })));
        this.orderChange.emit(this.pageList());
        this.orderStringChange.emit(toOrderString(this.pageList()));
      }

      this.scheduleObserve();
    } catch (e) {
      const msg =
        e instanceof Error ? e.message : this.transloco.translate('pageGrid.renderFailed');
      this.errorMsg.set(msg);
      this.renderError.emit(msg);
    } finally {
      if (token === this.renderToken) this.loading.set(false);
    }
  }

  private scheduleObserve(): void {
    // Let the template create/settle the tile elements, then (re)observe them.
    setTimeout(() => this.observeTiles(), 0);
  }

  private observeTiles(): void {
    const root = this.host.nativeElement.querySelector('.pg-grid') as HTMLElement | null;
    if (!root) return;
    if (!this.observer) {
      this.observer = new IntersectionObserver(
        (entries) => {
          for (const entry of entries) {
            if (entry.isIntersecting) void this.paintTile(entry.target as HTMLElement);
          }
        },
        { root, rootMargin: '200px' },
      );
    }
    const tiles = root.querySelectorAll('.pg-tile');
    tiles.forEach((el) => {
      if (this.observed.has(el)) return;
      this.observed.add(el);
      this.observer!.observe(el);
    });
  }

  private async paintTile(tileEl: HTMLElement): Promise<void> {
    const id = Number(tileEl.dataset['tile']);
    const page = Number(tileEl.dataset['page']);
    if (Number.isNaN(id) || Number.isNaN(page)) return;
    if (this.painted.has(id)) {
      this.observer?.unobserve(tileEl);
      return;
    }
    const token = this.renderToken;
    const src = await this.renderSourcePage(page, token);
    if (!src || token !== this.renderToken) return;
    const canvas = tileEl.querySelector('canvas.thumb') as HTMLCanvasElement | null;
    if (!canvas) return;
    canvas.width = src.width;
    canvas.height = src.height;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    ctx.drawImage(src, 0, 0);
    this.painted.add(id);
    this.observer?.unobserve(tileEl);
  }

  /** Render a page to a detached canvas exactly once; duplicates reuse the cache. */
  private async renderSourcePage(page: number, token: number): Promise<HTMLCanvasElement | null> {
    const cached = this.sourceCanvas.get(page);
    if (cached) return cached;
    const doc = this.doc;
    if (!doc) return null;
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    const pdfPage = await doc.getPage(page);
    if (token !== this.renderToken) return null;
    const base = pdfPage.getViewport({ scale: 1 });
    const scale = (this.thumbWidth * dpr) / base.width;
    const vp = pdfPage.getViewport({ scale });
    const canvas = document.createElement('canvas');
    canvas.width = Math.round(vp.width);
    canvas.height = Math.round(vp.height);
    const ctx = canvas.getContext('2d');
    if (!ctx) return null;
    await this.zone.runOutsideAngular(async () => {
      await pdfPage.render({ canvasContext: ctx, viewport: vp }).promise;
    });
    if (token !== this.renderToken) return null;
    this.sourceCanvas.set(page, canvas);
    return canvas;
  }
}
