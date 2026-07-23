import {
  AfterViewInit,
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

/** A rectangle region in PDF points, top-left origin, 0-based page index. */
export interface RegionRect {
  pageIndex: number;
  x: number;
  y: number;
  width: number;
  height: number;
}

interface PageMeta {
  pageNumber: number; // 1-based
  pageIndex: number; // 0-based
  cssWidth: number;
  cssHeight: number;
}

/**
 * Reusable pdf.js viewer. Renders each page to a `<canvas>` at the given display
 * scale; when `drawable` is set, the user can drag rectangles over a page which
 * are converted to PDF points (top-left origin, 0-based page index) and emitted.
 * Also usable as a lightweight preview/thumbnail (drawable off, optional
 * `singlePage`).
 */
@Component({
  selector: 'app-pdf-viewer',
  standalone: true,
  imports: [SpinnerComponent, TranslocoModule],
  template: `
    @if (loading()) {
      <div class="loading"><app-spinner [label]="'viewer.renderingPreview' | transloco" /></div>
    }
    @if (errorMsg()) {
      <p class="viewer-error" role="alert">{{ errorMsg() }}</p>
    }
    <div class="viewer-scroll">
    <div class="pages" [class.compact]="compact">
      @for (p of pages(); track p.pageNumber) {
        <div class="page-wrap">
          <div
            class="page"
            [style.width.px]="p.cssWidth"
            [style.height.px]="p.cssHeight"
          >
            <canvas
              class="pdf-canvas"
              [attr.data-idx]="p.pageIndex"
              [style.width.px]="p.cssWidth"
              [style.height.px]="p.cssHeight"
            ></canvas>
            @if (drawable) {
              <div
                class="draw-layer"
                (pointerdown)="onDown($event, p)"
                (pointermove)="onMove($event, p)"
                (pointerup)="onUp($event, p)"
                (pointercancel)="cancelDraw()"
              >
                @for (r of regionsFor(p.pageIndex); track $index) {
                  <div
                    class="region"
                    [style.left.px]="r.x * renderScale()"
                    [style.top.px]="r.y * renderScale()"
                    [style.width.px]="r.width * renderScale()"
                    [style.height.px]="r.height * renderScale()"
                  ></div>
                }
                @if (draft() && draft()!.pageIndex === p.pageIndex) {
                  <div
                    class="region draft"
                    [style.left.px]="draft()!.left"
                    [style.top.px]="draft()!.top"
                    [style.width.px]="draft()!.width"
                    [style.height.px]="draft()!.height"
                  ></div>
                }
              </div>
            }
          </div>
          @if (showPageLabels) {
            <span class="page-label">{{ 'viewer.pageCaption' | transloco: { n: p.pageNumber } }}</span>
          }
        </div>
      }
    </div>
    </div>
  `,
  styles: [
    `
      :host {
        display: block;
      }
      .loading {
        padding: 1rem 0;
      }
      .viewer-error {
        color: var(--danger);
        font-size: 0.9rem;
      }
      /* Plain horizontal-scroll wrapper: a wide page scrolls sideways instead of
         squishing. No transform/zoom/scale here — the render scale and all
         pixel↔PDF-point math stay untouched, and getBoundingClientRect used by
         the pointer/region code remains valid. */
      .viewer-scroll {
        max-width: 100%;
        overflow-x: auto;
        -webkit-overflow-scrolling: touch;
      }
      .pages {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 1rem;
        /* Grow to the widest page so an oversized page overflows the scroll
           wrapper (which scrolls) rather than being clipped by flex centering;
           min-width keeps narrow pages centered in the wrapper. */
        width: max-content;
        min-width: 100%;
      }
      .pages.compact {
        gap: 0.5rem;
      }
      .page-wrap {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 0.35rem;
      }
      .page {
        position: relative;
        box-shadow: var(--shadow);
        border: 1px solid var(--border);
        background: #fff;
        /* Never let a page box exceed the wrapper width — fit-to-width sizing
           keeps this from clipping landscape pages; this is a belt-and-braces
           guard so an oversized ancestor with overflow:hidden can't clip. */
        max-width: 100%;
      }
      .pdf-canvas {
        display: block;
        max-width: 100%;
      }
      .draw-layer {
        position: absolute;
        inset: 0;
        cursor: crosshair;
        touch-action: none;
      }
      .region {
        position: absolute;
        background: rgba(209, 61, 61, 0.35);
        border: 1.5px solid var(--danger);
        pointer-events: none;
      }
      .region.draft {
        background: rgba(47, 111, 237, 0.25);
        border-color: var(--accent);
      }
      .page-label {
        font-size: 0.75rem;
        color: var(--text-muted);
      }
    `,
  ],
})
export class PdfViewerComponent implements AfterViewInit, OnDestroy {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly zone = inject(NgZone);
  private readonly transloco = inject(TranslocoService);

  /** Display scale: PDF points → CSS pixels. */
  @Input() scale = 1.25;
  /** Enable rectangle drawing for redaction region picking. */
  @Input() drawable = false;
  /** Render only this page (1-based); omit to render all. */
  @Input() singlePage?: number;
  /** Show a "Page N" caption under each page. */
  @Input() showPageLabels = true;
  /** Tighter spacing (thumbnails). */
  @Input() compact = false;

  @Input()
  set file(value: File | Blob | null | undefined) {
    if (value === this._file) return;
    this._file = value ?? null;
    void this.reload();
  }

  @Output() regionsChange = new EventEmitter<RegionRect[]>();
  @Output() loaded = new EventEmitter<{ pages: number }>();
  @Output() renderError = new EventEmitter<string>();

  readonly pages = signal<PageMeta[]>([]);
  readonly loading = signal(false);
  readonly errorMsg = signal<string | null>(null);
  /**
   * Effective display scale (PDF points → CSS pixels) actually used to size and
   * render pages. Defaults to the requested `scale` and is ONLY reduced below it
   * when a valid, positive container width proves the widest page would overflow
   * (fit-to-width) — so portrait/normal pages always render full size and only
   * wide/landscape pages shrink to avoid right-side clipping. It is never derived
   * from a 0/unmeasured width (that path keeps the requested scale), so pages can
   * never collapse to a sliver. All pixel↔point math (region rendering,
   * draw→point conversion, canvas paint) reads this single factor, so the
   * conversion stays consistent across every page.
   */
  readonly renderScale = signal(this.scale);
  readonly draft = signal<{
    pageIndex: number;
    left: number;
    top: number;
    width: number;
    height: number;
  } | null>(null);

  private readonly regions = signal<RegionRect[]>([]);
  private _file: File | Blob | null = null;
  private doc: PDFDocumentProxy | null = null;
  private renderToken = 0;
  private dragStart: { x: number; y: number } | null = null;

  /** Intrinsic page sizes in PDF points (scale = 1), captured on load so the
   *  fit-to-width scale can be recomputed on resize without reloading the doc. */
  private ptSizes: { n: number; w: number; h: number }[] = [];
  private maxPtWidth = 0;
  private range: { first: number; last: number } = { first: 1, last: 1 };
  /** Last container width (CSS px) a fit was applied for. Guards against
   *  re-fitting when the available width hasn't actually changed, so once the
   *  preview settles no further scale changes/repaints fire. -1 = not yet fit. */
  private lastFitWidth = -1;
  /** Pending rAF id for the debounced window-resize refit (0 = none). */
  private resizeRaf = 0;
  /** Debounced window-resize handler: coalesces bursts into one rAF-timed refit.
   *  Re-fits ONLY on genuine window resizes — never observes a content-affected
   *  element, so a repaint can never feed back and shrink the preview. */
  private readonly onWindowResize = (): void => {
    if (this.resizeRaf) return;
    this.resizeRaf = requestAnimationFrame(() => {
      this.resizeRaf = 0;
      this.refitToWidth();
    });
  };

  regionsFor(pageIndex: number): RegionRect[] {
    return this.regions().filter((r) => r.pageIndex === pageIndex);
  }

  /** Current regions (PDF points). */
  getRegions(): RegionRect[] {
    return this.regions();
  }

  /**
   * Replace the drawn regions with a pre-computed set (PDF points, top-left,
   * 0-based page index) — used to seed boxes handed off from the GDPR scan.
   * They render via the same `r.x * scale` mapping as user-drawn boxes, so no
   * conversion is needed here: the input is already in the viewer's point space.
   */
  setRegions(regions: RegionRect[]): void {
    const next = regions.slice();
    this.regions.set(next);
    this.regionsChange.emit(next);
  }

  removeRegion(index: number): void {
    const next = this.regions().slice();
    next.splice(index, 1);
    this.regions.set(next);
    this.regionsChange.emit(next);
  }

  clearRegions(): void {
    this.regions.set([]);
    this.regionsChange.emit([]);
  }

  ngAfterViewInit(): void {
    // A ResizeObserver on the host (or any content-affected element) fed back:
    // fit → shrink content → host shrinks → fit smaller → … an infinite shrink.
    // Instead we fit ONCE after layout (a single rAF here, plus the one-shot in
    // reload()) and thereafter only on a genuine WINDOW resize. `refitToWidth`
    // bails unless the measured available width actually changed, so the preview
    // settles to one stable size and never continuously shrinks.
    requestAnimationFrame(() => this.refitToWidth());
    window.addEventListener('resize', this.onWindowResize);
  }

  ngOnDestroy(): void {
    window.removeEventListener('resize', this.onWindowResize);
    if (this.resizeRaf) cancelAnimationFrame(this.resizeRaf);
    void this.doc?.destroy();
  }

  private async reload(): Promise<void> {
    const token = ++this.renderToken;
    this.pages.set([]);
    this.regions.set([]);
    this.regionsChange.emit([]);
    this.errorMsg.set(null);
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

      const first = this.singlePage ?? 1;
      const last = this.singlePage ?? doc.numPages;
      this.range = { first, last };

      // Pass 1: collect each page's intrinsic size in PDF points (scale = 1).
      const ptSizes: { n: number; w: number; h: number }[] = [];
      let maxPtWidth = 0;
      for (let n = first; n <= last; n++) {
        const page = await doc.getPage(n);
        const vp1 = page.getViewport({ scale: 1 });
        ptSizes.push({ n, w: vp1.width, h: vp1.height });
        if (vp1.width > maxPtWidth) maxPtWidth = vp1.width;
      }
      if (token !== this.renderToken) return;
      this.ptSizes = ptSizes;
      this.maxPtWidth = maxPtWidth;

      // First layout at the requested scale (full size) — deliberately NOT from a
      // measurement here, because the file may have been set before the viewer
      // was laid out, and a 0/tiny width at this moment would collapse the scale.
      // `applyFitScale()` then narrows it only if a VALID width proves overflow.
      this.renderScale.set(this.scale);
      this.lastFitWidth = -1; // new document: force the next fit to measure fresh
      this.layoutPages();
      this.loaded.emit({ pages: doc.numPages });

      // Let the template create the <canvas> elements (and the container gain
      // layout width), then measure and fit-to-width ONCE before the single paint.
      await new Promise<void>((resolve) => setTimeout(resolve, 0));
      if (token !== this.renderToken) return;
      this.applyFitScale();
      await this.paint(doc, first, last, token);
    } catch (e) {
      const msg =
        e instanceof Error ? e.message : this.transloco.translate('viewer.renderError');
      this.errorMsg.set(msg);
      this.renderError.emit(msg);
    } finally {
      if (token === this.renderToken) this.loading.set(false);
    }
  }

  /** Build `pages()` metas from the cached point sizes at the current render scale. */
  private layoutPages(): void {
    const eff = this.renderScale();
    this.pages.set(
      this.ptSizes.map((s) => ({
        pageNumber: s.n,
        pageIndex: s.n - 1,
        cssWidth: Math.round(s.w * eff),
        cssHeight: Math.round(s.h * eff),
      })),
    );
  }

  /**
   * Effective scale to render at: the requested `scale`, reduced ONLY when a
   * valid positive container width proves the widest page would overflow it
   * (fit-to-width for landscape/wide pages). With no valid width, or when the
   * page already fits, the requested `scale` is returned unchanged — so a
   * portrait/normal page is never shrunk and an unmeasured (0-width) container
   * can never drive the scale toward 0. Every division is guarded.
   */
  private fitScale(cw: number): number {
    if (cw > 0 && this.maxPtWidth > 0) {
      const fit = cw / this.maxPtWidth; // px-per-point that makes the widest page fit
      if (fit > 0 && fit < this.scale) return fit; // only ever downscale
    }
    return this.scale;
  }

  /**
   * Measure the available (content-independent) width once and apply the
   * fit-to-width scale if it changed. Returns true when the render scale was
   * updated (a repaint is then needed). A 0/invalid width is a no-op that KEEPS
   * the requested scale (never scales toward 0). Records `lastFitWidth` so a
   * later refit at the same width bails — this is what makes the preview settle.
   */
  private applyFitScale(): boolean {
    const cw = this.measureContainerWidth();
    if (cw <= 0) return false; // no valid width — keep the requested scale
    this.lastFitWidth = cw;
    const eff = this.fitScale(cw);
    if (Math.abs(eff - this.renderScale()) < 0.001) return false;
    this.renderScale.set(eff);
    this.layoutPages();
    return true;
  }

  /**
   * Re-fit after a genuine WINDOW resize (or the one-shot post-layout rAF). Bails
   * unless a document is loaded AND the measured available width actually changed
   * vs the last applied width — so once settled, no further refits/repaints fire
   * and there is no self-observing feedback loop. Repaints only when the scale
   * changed. No-op until a document is loaded.
   */
  private refitToWidth(): void {
    if (!this.ptSizes.length || !this.doc) return;
    const cw = this.measureContainerWidth();
    if (cw <= 0 || cw === this.lastFitWidth) return; // settled / no valid width
    if (!this.applyFitScale()) return;
    const doc = this.doc;
    const token = this.renderToken;
    // Let the new page-box sizes settle, then repaint the canvases at the new scale.
    setTimeout(() => {
      if (token !== this.renderToken || this.doc !== doc) return;
      void this.paint(doc, this.range.first, this.range.last, token);
    }, 0);
  }

  /**
   * Width (CSS px) available for a page, from the scroll wrapper's content box
   * (excludes its scrollbar). The wrapper is block-level (`max-width:100%`), so
   * its clientWidth reflects the AVAILABLE width independent of page content —
   * measuring it can't feed back into the fit-to-width scale. A small margin
   * covers the `.page` border so an exactly-fitted page doesn't spill 1–2px and
   * trigger a scroll/clip. Returns 0 when the viewer isn't laid out yet
   * (offscreen/collapsed) — callers then fall back to the requested scale.
   */
  private measureContainerWidth(): number {
    const root = this.host.nativeElement as HTMLElement;
    const scroll = root.querySelector('.viewer-scroll') as HTMLElement | null;
    const w = scroll?.clientWidth || root.clientWidth || 0;
    return w > 0 ? Math.max(0, w - 2) : 0;
  }

  private async paint(
    doc: PDFDocumentProxy,
    first: number,
    last: number,
    token: number,
  ): Promise<void> {
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    const root = this.host.nativeElement as HTMLElement;
    const canvases = Array.from(
      root.querySelectorAll('canvas.pdf-canvas'),
    ) as HTMLCanvasElement[];
    for (let n = first; n <= last; n++) {
      if (token !== this.renderToken) return;
      const canvas = canvases.find((c) => c.getAttribute('data-idx') === String(n - 1));
      if (!canvas) continue;
      const page = await doc.getPage(n);
      const vp = page.getViewport({ scale: this.renderScale() * dpr });
      canvas.width = Math.round(vp.width);
      canvas.height = Math.round(vp.height);
      const ctx = canvas.getContext('2d');
      if (!ctx) continue;
      await this.zone.runOutsideAngular(async () => {
        await page.render({ canvasContext: ctx, viewport: vp }).promise;
      });
    }
  }

  // ---- Region drawing ---------------------------------------------------

  onDown(ev: PointerEvent, page: PageMeta): void {
    if (!this.drawable) return;
    (ev.target as HTMLElement).setPointerCapture?.(ev.pointerId);
    const rect = (ev.currentTarget as HTMLElement).getBoundingClientRect();
    this.dragStart = { x: ev.clientX - rect.left, y: ev.clientY - rect.top };
    this.draft.set({ pageIndex: page.pageIndex, left: this.dragStart.x, top: this.dragStart.y, width: 0, height: 0 });
  }

  onMove(ev: PointerEvent, page: PageMeta): void {
    if (!this.dragStart) return;
    const rect = (ev.currentTarget as HTMLElement).getBoundingClientRect();
    const cx = Math.max(0, Math.min(ev.clientX - rect.left, page.cssWidth));
    const cy = Math.max(0, Math.min(ev.clientY - rect.top, page.cssHeight));
    const left = Math.min(this.dragStart.x, cx);
    const top = Math.min(this.dragStart.y, cy);
    this.draft.set({
      pageIndex: page.pageIndex,
      left,
      top,
      width: Math.abs(cx - this.dragStart.x),
      height: Math.abs(cy - this.dragStart.y),
    });
  }

  onUp(ev: PointerEvent, page: PageMeta): void {
    const d = this.draft();
    this.dragStart = null;
    this.draft.set(null);
    if (!d || d.width < 4 || d.height < 4) return;
    // CSS pixels → PDF points (top-left origin) by dividing out the effective
    // display scale (fit-to-width may have shrunk it below the requested scale).
    const s = this.renderScale();
    const region: RegionRect = {
      pageIndex: page.pageIndex,
      x: +(d.left / s).toFixed(2),
      y: +(d.top / s).toFixed(2),
      width: +(d.width / s).toFixed(2),
      height: +(d.height / s).toFixed(2),
    };
    const next = [...this.regions(), region];
    this.regions.set(next);
    this.regionsChange.emit(next);
  }

  cancelDraw(): void {
    this.dragStart = null;
    this.draft.set(null);
  }
}
