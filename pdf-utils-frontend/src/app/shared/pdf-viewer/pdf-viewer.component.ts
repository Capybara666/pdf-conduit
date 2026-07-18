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
                    [style.left.px]="r.x * scale"
                    [style.top.px]="r.y * scale"
                    [style.width.px]="r.width * scale"
                    [style.height.px]="r.height * scale"
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
      .pages {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 1rem;
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
      }
      .pdf-canvas {
        display: block;
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
export class PdfViewerComponent implements OnDestroy {
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

  ngOnDestroy(): void {
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

      const metas: PageMeta[] = [];
      const first = this.singlePage ?? 1;
      const last = this.singlePage ?? doc.numPages;
      for (let n = first; n <= last; n++) {
        const page = await doc.getPage(n);
        const vp = page.getViewport({ scale: this.scale });
        metas.push({
          pageNumber: n,
          pageIndex: n - 1,
          cssWidth: Math.round(vp.width),
          cssHeight: Math.round(vp.height),
        });
      }
      if (token !== this.renderToken) return;
      this.pages.set(metas);
      this.loaded.emit({ pages: doc.numPages });

      // Let the template create the <canvas> elements, then paint each.
      await new Promise<void>((resolve) => setTimeout(resolve, 0));
      if (token !== this.renderToken) return;
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
      const vp = page.getViewport({ scale: this.scale * dpr });
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
    // CSS pixels → PDF points (top-left origin) by dividing out the display scale.
    const region: RegionRect = {
      pageIndex: page.pageIndex,
      x: +(d.left / this.scale).toFixed(2),
      y: +(d.top / this.scale).toFixed(2),
      width: +(d.width / this.scale).toFixed(2),
      height: +(d.height / this.scale).toFixed(2),
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
