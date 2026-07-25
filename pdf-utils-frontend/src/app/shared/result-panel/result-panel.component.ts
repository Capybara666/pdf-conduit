import {
  Component,
  ComponentRef,
  ElementRef,
  EventEmitter,
  Input,
  OnChanges,
  OnDestroy,
  Output,
  SimpleChanges,
  ViewChild,
  ViewContainerRef,
  inject,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';

import { ApiError, BatchFailuresInfo, RedactionInfo, RunResult } from '../../core/api.models';
import { downloadRunResult, formatBytes } from '../../core/download.util';
import { errorCopyKeys } from '../../core/error-copy';
import { SpinnerComponent } from '../spinner/spinner.component';
import { countZipEntries } from './zip-entries.util';

/** Resolved, translated error copy for display in the panel. */
interface ResolvedCopy {
  title: string;
  detail: string;
  hint?: string;
  proLink?: boolean;
}

/** Which lifecycle state the panel is currently showing. */
type Phase = 'idle' | 'loading' | 'error' | 'success';

/**
 * Backend `RepairFinding` id → plain-language i18n key.
 *
 * The wire ids (`startxref-invalid`, `xref-rebuilt`, …) are engineering
 * vocabulary and must never reach the screen. An id missing from this map is a
 * finding from a newer backend: it is dropped, so an unknown value degrades to
 * one fewer line rather than to jargon or an error.
 */
const REPAIR_FINDING_KEYS: Readonly<Record<string, string>> = {
  'header-missing': 'result.repairFinding.headerMissing',
  'header-offset': 'result.repairFinding.headerOffset',
  'eof-missing': 'result.repairFinding.eofMissing',
  'startxref-missing': 'result.repairFinding.startxrefMissing',
  'startxref-invalid': 'result.repairFinding.startxrefInvalid',
  'xref-rebuilt': 'result.repairFinding.xrefRebuilt',
  'rebuild-incomplete': 'result.repairFinding.rebuildIncomplete',
};

/**
 * Presentational panel for the outcome of an operation: a loading state, a
 * typed error (with a "Try again" action), a partial batch (some inputs
 * skipped), or a success with a download button and — for single-PDF /
 * single-image outputs — a lightweight, click-to-expand first-page preview.
 *
 * Beyond "Done" it reports what the server measured, and only that: compression
 * sizes, what a repair found and fixed, how much a redaction actually blacked
 * out, and which inputs a batch could not process. Every one of those rests on
 * a response header, so when a header is absent the corresponding line simply
 * does not appear — the panel never fills a gap with a plausible-looking zero.
 *
 * Operation forms feed it `[loading]`, `[error]` and `[result]`, and may bind
 * `(retry)` to re-run the operation.
 *
 * pdf.js is only ever reached through a dynamic `import()` when a PDF result
 * actually needs previewing, so pages that embed this panel never pull the
 * renderer into their own bundle.
 */
@Component({
  selector: 'app-result-panel',
  standalone: true,
  imports: [SpinnerComponent, RouterLink, TranslocoModule],
  templateUrl: './result-panel.component.html',
  styleUrl: './result-panel.component.scss',
})
export class ResultPanelComponent implements OnChanges, OnDestroy {
  private readonly transloco = inject(TranslocoService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  @Input() loading = false;
  @Input() loadingLabel = '';
  @Input() error: ApiError | null = null;
  @Input() result: RunResult | null = null;

  /** Emitted when the user clicks "Try again" on the error state. */
  @Output() retry = new EventEmitter<void>();

  protected readonly formatBytes = formatBytes;

  /** First-page thumbnail (PDF) or object URL (single image), if previewable. */
  protected readonly previewImageUrl = signal<string | null>(null);
  protected readonly previewPdfThumb = signal<string | null>(null);
  /** Whether the enlarged lightbox is open. */
  protected readonly expanded = signal(false);
  /**
   * How many files a partial batch actually returned, read out of the ZIP's own
   * directory; `null` while it is being read, or for good when the archive
   * cannot be counted. Only then can the panel say "12 of 15".
   */
  private readonly deliveredCount = signal<number | null>(null);

  /** Anchor for the lazily-created full PdfViewerComponent inside the lightbox. */
  @ViewChild('viewerHost', { read: ViewContainerRef }) private viewerHost?: ViewContainerRef;

  private objectUrl: string | null = null;
  private previewPdfBlob: Blob | null = null;
  private previewToken = 0;
  private prevPhase: Phase = 'idle';
  private viewerRef?: ComponentRef<unknown>;

  ngOnChanges(_changes: SimpleChanges): void {
    const phase = this.phase();
    if (phase !== this.prevPhase) {
      this.prevPhase = phase;
      if (phase !== 'idle') this.scrollIntoView();
      // A fresh result / new run invalidates any preview or open lightbox.
      this.closeExpand();
      this.deliveredCount.set(null);
      if (phase === 'success') {
        void this.buildPreview(this.result!);
        void this.countDelivered(this.result!);
      } else {
        this.clearPreview();
      }
    }
  }

  ngOnDestroy(): void {
    this.clearPreview();
    this.viewerRef?.destroy();
  }

  /** Current lifecycle state (loading wins, then error, then result). */
  private phase(): Phase {
    if (this.loading) return 'loading';
    if (this.error) return 'error';
    if (this.result) return 'success';
    return 'idle';
  }

  /** Friendly, code-aware, translated presentation copy for the current error. */
  get copy(): ResolvedCopy | null {
    if (!this.error) return null;
    const keys = errorCopyKeys(this.error);
    return {
      title: this.transloco.translate(keys.titleKey),
      detail: keys.detailText || (keys.detailKey ? this.transloco.translate(keys.detailKey) : ''),
      hint: keys.hintKey ? this.transloco.translate(keys.hintKey, keys.hintParams) : undefined,
      proLink: keys.proLink,
    };
  }

  download(): void {
    if (this.result) {
      downloadRunResult(this.result);
    }
  }

  onRetry(): void {
    this.retry.emit();
  }

  get savedPercent(): number | null {
    const c = this.result?.compression;
    if (!c || !c.originalBytes || c.resultBytes == null) return null;
    return Math.max(0, Math.round((1 - c.resultBytes / c.originalBytes) * 100));
  }

  /**
   * i18n key for the honest one-line repair outcome, or `null` when there is
   * nothing trustworthy to say. Only a single-file repair response carries the
   * `X-Repair-*` headers; a batch (ZIP) run, another operation, or a server that
   * predates the headers leaves the panel on the plain success copy — never a
   * guessed claim about the file.
   */
  get repairNoteKey(): string | null {
    const r = this.result?.repair;
    if (!r) return null;
    if (r.wasDamaged === false) return 'result.repairAlreadyFine';
    if (r.wasDamaged === true) {
      return r.recovered === false ? 'result.repairPartial' : 'result.repairRebuilt';
    }
    return null;
  }

  /**
   * The defects the repair found, as plain-language i18n keys.
   *
   * Empty whenever there is nothing the headers support: no `X-Repair-Findings`
   * at all, an empty one (the honest "nothing was wrong" value), or only ids
   * this build does not recognise. The template renders nothing for an empty
   * list, so every one of those degrades to silence — never to an error, and
   * never to a raw `startxref-invalid` on screen.
   */
  get repairFindingKeys(): string[] {
    const ids = this.result?.repair?.findings;
    if (!ids?.length) return [];
    const keys: string[] = [];
    for (const id of ids) {
      const key = REPAIR_FINDING_KEYS[id];
      if (key && !keys.includes(key)) keys.push(key);
    }
    return keys;
  }

  /** Page count of a repaired document, when the server reported one. */
  get repairPageCount(): number | null {
    const pages = this.result?.repair?.pageCount;
    return typeof pages === 'number' && pages > 0 ? pages : null;
  }

  /**
   * What a redaction actually blacked out, or `null` when there is nothing
   * measured to show. A response without the headers (older backend, or a
   * non-redaction operation) and a reported zero both yield `null`: the panel
   * would rather say nothing than print a coverage figure it cannot stand
   * behind on the one operation where that matters most.
   */
  get redaction(): Required<RedactionInfo> | null {
    const r = this.result?.redaction;
    if (!r || !r.regions || !r.pages) return null;
    return { regions: r.regions, pages: r.pages };
  }

  /** The failures of a partial batch, or `null` when every input succeeded. */
  get batchFailures(): BatchFailuresInfo | null {
    return this.result?.batchFailures ?? null;
  }

  /**
   * "12 of 15 files processed" — but only when both numbers are real: the
   * delivered count comes from the returned archive and the failure count from
   * the header. If the archive could not be counted this stays `null` and the
   * panel reports the failures alone.
   */
  get processedCounts(): { done: number; total: number } | null {
    const failures = this.batchFailures;
    const done = this.deliveredCount();
    if (!failures || done == null) return null;
    return { done, total: done + failures.total };
  }

  /**
   * For a partial batch, read how many files the returned archive actually
   * contains. Failure to read it is not an error state — `deliveredCount` just
   * stays `null` and the panel drops the "of N" half of the summary.
   */
  private async countDelivered(result: RunResult): Promise<void> {
    if (!result.batchFailures) return;
    const count = await countZipEntries(result.blob);
    // A late answer for a superseded run must not land on the new one.
    if (this.result === result) this.deliveredCount.set(count);
  }

  /** True when a preview (image or PDF thumbnail) is available to expand. */
  protected hasPreview(): boolean {
    return this.previewImageUrl() !== null || this.previewPdfThumb() !== null;
  }

  // ---- Preview -----------------------------------------------------------

  private isImageResult(r: RunResult): boolean {
    const ct = (r.contentType || r.blob.type || '').toLowerCase();
    if (ct.startsWith('image/')) return true;
    return /\.(png|jpe?g|gif|bmp|webp|tiff?)$/i.test(r.filename || '');
  }

  private isPdfResult(r: RunResult): boolean {
    const ct = (r.contentType || r.blob.type || '').toLowerCase();
    if (ct.includes('pdf')) return true;
    return /\.pdf$/i.test(r.filename || '');
  }

  /**
   * Build a lightweight preview for the produced result. Single images show an
   * inline <img>; single PDFs render ONLY their first page to a small thumbnail
   * (pdf.js dynamically imported). ZIP and text/plain results get no preview.
   */
  private async buildPreview(result: RunResult): Promise<void> {
    this.clearPreview();
    const token = ++this.previewToken;

    if (this.isImageResult(result)) {
      const url = URL.createObjectURL(result.blob);
      this.objectUrl = url;
      this.previewImageUrl.set(url);
      return;
    }

    if (!this.isPdfResult(result)) return; // ZIP / text / anything else: skip.

    this.previewPdfBlob = result.blob;
    try {
      const { loadPdf } = await import('../../core/pdfjs');
      if (token !== this.previewToken) return;
      const buf = await result.blob.arrayBuffer();
      const doc = await loadPdf(buf);
      try {
        if (token !== this.previewToken) return;
        const page = await doc.getPage(1);
        const dpr = Math.min(window.devicePixelRatio || 1, 2);
        const base = page.getViewport({ scale: 1 });
        const scale = (140 * dpr) / base.width;
        const vp = page.getViewport({ scale });
        const canvas = document.createElement('canvas');
        canvas.width = Math.round(vp.width);
        canvas.height = Math.round(vp.height);
        const ctx = canvas.getContext('2d');
        if (ctx) {
          await page.render({ canvasContext: ctx, viewport: vp }).promise;
          if (token === this.previewToken) this.previewPdfThumb.set(canvas.toDataURL('image/png'));
        }
      } finally {
        void doc.destroy();
      }
    } catch {
      // No preview on failure — the download button still works fine.
    }
  }

  private clearPreview(): void {
    this.previewToken++;
    this.previewImageUrl.set(null);
    this.previewPdfThumb.set(null);
    this.previewPdfBlob = null;
    if (this.objectUrl) {
      URL.revokeObjectURL(this.objectUrl);
      this.objectUrl = null;
    }
  }

  // ---- Lightbox (click-to-expand) ---------------------------------------

  openExpand(): void {
    if (!this.hasPreview()) return;
    this.expanded.set(true);
    if (this.previewPdfBlob) {
      // Defer until the lightbox (and its #viewerHost anchor) is in the DOM,
      // then lazily instantiate the full viewer — this is the only path that
      // pulls the pdf.js-backed component, so it stays out of page bundles.
      setTimeout(() => void this.mountViewer(), 0);
    }
  }

  private async mountViewer(): Promise<void> {
    if (!this.expanded() || !this.viewerHost || !this.previewPdfBlob || this.viewerRef) return;
    const blob = this.previewPdfBlob;
    const { PdfViewerComponent } = await import('../pdf-viewer/pdf-viewer.component');
    if (!this.expanded() || !this.viewerHost) return;
    const ref = this.viewerHost.createComponent(PdfViewerComponent);
    ref.setInput('file', blob);
    ref.setInput('scale', 1.1);
    ref.setInput('showPageLabels', true);
    this.viewerRef = ref;
  }

  closeExpand(): void {
    if (this.expanded()) this.expanded.set(false);
    this.viewerRef?.destroy();
    this.viewerRef = undefined;
    this.viewerHost?.clear();
  }

  // ---- Scroll ------------------------------------------------------------

  /** Bring the panel into view when a new outcome appears (respecting motion prefs). */
  private scrollIntoView(): void {
    const reduce =
      typeof window !== 'undefined' &&
      window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches;
    setTimeout(() => {
      this.host.nativeElement.scrollIntoView({
        behavior: reduce ? 'auto' : 'smooth',
        block: 'nearest',
      });
    }, 0);
  }
}
