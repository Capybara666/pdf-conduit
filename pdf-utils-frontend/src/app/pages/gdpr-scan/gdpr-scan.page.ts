import { NgClass } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';

import { ApiError, BatchPiiReport, PiiReport, RedactRegion } from '../../core/api.models';
import { ApiService } from '../../core/api.service';
import { downloadRunResult } from '../../core/download.util';
import { ResolvedErrorCopy, resolveErrorCopy } from '../../core/error-copy';
import { RedactHandoffService } from '../../core/redact-handoff.service';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
import { SpinnerComponent } from '../../shared/spinner/spinner.component';

/**
 * The GDPR categories the scanner can actually populate, ordered high-risk first;
 * `high` drives the emphasis styling. The core `IDENTIFIER` category is intentionally
 * omitted: it is reserved for future detectors and has none today, so no finding ever
 * carries it — showing it would be a permanently-empty, misleading card.
 */
const CATEGORY_ORDER: readonly { key: string; high: boolean }[] = [
  { key: 'FINANCIAL', high: true },
  { key: 'NATIONAL_ID', high: true },
  { key: 'SPECIAL_CATEGORY', high: true },
  { key: 'CONTACT', high: false },
  { key: 'ONLINE_IDENTIFIER', high: false },
];

/** A category card: enum key, distinct-finding count, and whether it's a higher-risk category. */
interface CategoryView {
  key: string;
  count: number;
  high: boolean;
}

/** A per-file row in the batch audit: file object, its report, and whether it has redactable data. */
interface FileRow {
  file: File;
  report: PiiReport;
  redactable: boolean;
}

/**
 * GDPR / PII scanner report page. Upload one PDF (or image / office doc) for a full report, or
 * several files for an aggregated compliance audit — scanned entirely in memory on the server.
 * The report is privacy-first: an overall risk badge, per-category cards, and a masked findings
 * table. From here you can hand off to the manual Redact tool (pre-seeded boxes) or run a free
 * one-click auto-redaction that blacks out every detected value. Nothing is stored; only masked
 * samples ever leave the server.
 */
@Component({
  selector: 'app-gdpr-scan-page',
  standalone: true,
  imports: [
    NgClass,
    TranslocoModule,
    FileDropZoneComponent,
    PageHeaderComponent,
    SpinnerComponent,
  ],
  templateUrl: './gdpr-scan.page.html',
  styleUrl: './gdpr-scan.page.scss',
})
export class GdprScanPage {
  private readonly api = inject(ApiService);
  private readonly handoff = inject(RedactHandoffService);
  private readonly router = inject(Router);
  private readonly transloco = inject(TranslocoService);

  protected readonly files = signal<File[]>([]);
  protected readonly loading = signal(false);
  protected readonly error = signal<ApiError | null>(null);
  protected readonly report = signal<PiiReport | null>(null);
  protected readonly batch = signal<BatchPiiReport | null>(null);

  /** Active category filter for the single-file findings table (null = show all). */
  protected readonly filter = signal<string | null>(null);

  /**
   * Auto-redaction in flight: the filename currently being redacted (single files use their own
   * name), or `'*'` while a "redact all" sweep runs. Null when idle.
   */
  protected readonly redacting = signal<string | null>(null);

  /** Lower-cased single-file risk (`none`/`low`/`medium`/`high`) for styling + copy keys. */
  protected readonly riskKey = computed(() => (this.report()?.risk ?? 'NONE').toLowerCase());

  /** Lower-cased aggregate (highest) risk across the batch. */
  protected readonly batchRiskKey = computed(() =>
    (this.batch()?.highestRisk ?? 'NONE').toLowerCase(),
  );

  /** All six categories with the single-file counts, high-risk first (0-count cards still shown). */
  protected readonly categories = computed<CategoryView[]>(() =>
    this.toCategoryViews(this.report()?.countsByCategory ?? {}),
  );

  /** All six categories with the aggregate batch counts, high-risk first. */
  protected readonly batchCategories = computed<CategoryView[]>(() =>
    this.toCategoryViews(this.batch()?.countsByCategory ?? {}),
  );

  /** Distinct category keys actually present in the single-file report — for the filter chips. */
  protected readonly presentCategories = computed<string[]>(() =>
    this.categories()
      .filter((c) => c.count > 0)
      .map((c) => c.key),
  );

  /**
   * True when at least one finding carries redact regions — the free redact CTAs only appear then
   * (special-category keyword flags have no regions to black out).
   */
  protected readonly hasRedactable = computed(() =>
    (this.report()?.findings ?? []).some((f) => f.regions?.length),
  );

  /** Per-file rows for the batch view, pairing each uploaded file with its report (order preserved). */
  protected readonly fileRows = computed<FileRow[]>(() => {
    const b = this.batch();
    if (!b) return [];
    const uploaded = this.files();
    return b.files.map((entry, i) => ({
      file: uploaded[i] ?? new File([], entry.filename),
      report: entry.report,
      redactable: (entry.report.findings ?? []).some((f) => f.regions?.length),
    }));
  });

  /** True when any file in the batch has redactable data (drives the "redact all" button). */
  protected readonly batchHasRedactable = computed(() => this.fileRows().some((r) => r.redactable));

  /** Findings honouring the active category filter (single-file view). */
  protected readonly visibleFindings = computed(() => {
    const r = this.report();
    if (!r) return [];
    const f = this.filter();
    return f ? r.findings.filter((x) => x.category === f) : r.findings;
  });

  onFiles(files: File[]): void {
    this.files.set(files);
    this.error.set(null);
    this.report.set(null);
    this.batch.set(null);
    this.filter.set(null);
  }

  scan(): void {
    const files = this.files();
    if (!files.length || this.loading()) return;
    this.loading.set(true);
    this.error.set(null);
    this.report.set(null);
    this.batch.set(null);
    this.filter.set(null);

    const fd = new FormData();
    if (files.length === 1) {
      fd.append('file', files[0], files[0].name);
      this.api.gdprScan(fd).subscribe({
        next: (report) => {
          this.report.set(report);
          this.loading.set(false);
        },
        error: (e) => this.fail(e),
      });
    } else {
      for (const f of files) fd.append('files', f, f.name);
      this.api.gdprScanBatch(fd).subscribe({
        next: (batch) => {
          this.batch.set(batch);
          this.loading.set(false);
        },
        error: (e) => this.fail(e),
      });
    }
  }

  setFilter(key: string | null): void {
    this.filter.set(this.filter() === key ? null : key);
  }

  /**
   * Free manual redaction handoff (single file): gather every finding's regions (already in the
   * redact viewer's point space), stash them with the scanned file, then open the redact page —
   * which pre-draws the boxes for review before applying.
   */
  redactDetected(): void {
    const f = this.files()[0];
    const r = this.report();
    if (!f || !r) return;
    const regions = this.dedupe(r.findings.flatMap((x) => x.regions ?? []));
    if (!regions.length) return;
    this.handoff.set(f, regions);
    this.router.navigate(['/', 'redact']);
  }

  /** Free one-click auto-redaction of a single file: black out every detected value and download. */
  autoRedact(file: File): void {
    if (this.redacting()) return;
    this.redacting.set(file.name);
    this.error.set(null);
    const fd = new FormData();
    fd.append('file', file, file.name);
    this.api.autoRedact(fd).subscribe({
      next: (result) => {
        downloadRunResult(result);
        this.redacting.set(null);
      },
      error: (e) => {
        this.redacting.set(null);
        this.error.set(e instanceof ApiError ? e : new ApiError('unknown', String(e), 0));
      },
    });
  }

  /** Bulk auto-redact every batch file that carries detected data, one download each (sequential). */
  autoRedactAll(): void {
    if (this.redacting()) return;
    const targets = this.fileRows().filter((r) => r.redactable);
    if (!targets.length) return;
    this.redacting.set('*');
    this.error.set(null);
    this.redactNext(targets, 0);
  }

  /** Sequentially POST each target to /api/auto-redact so downloads don't stampede in parallel. */
  private redactNext(targets: FileRow[], i: number): void {
    if (i >= targets.length) {
      this.redacting.set(null);
      return;
    }
    const fd = new FormData();
    fd.append('file', targets[i].file, targets[i].file.name);
    this.api.autoRedact(fd).subscribe({
      next: (result) => {
        downloadRunResult(result);
        this.redactNext(targets, i + 1);
      },
      error: (e) => {
        this.redacting.set(null);
        this.error.set(e instanceof ApiError ? e : new ApiError('unknown', String(e), 0));
      },
    });
  }

  private fail(e: unknown): void {
    this.error.set(e instanceof ApiError ? e : new ApiError('unknown', String(e), 0));
    this.loading.set(false);
  }

  /** Map a `{category: count}` object to the fixed category view list, high-risk first. */
  private toCategoryViews(counts: Record<string, number>): CategoryView[] {
    return CATEGORY_ORDER.map((c) => ({ key: c.key, high: c.high, count: counts[c.key] ?? 0 }));
  }

  /** Drop exact-duplicate boxes (same page + rounded rect) to avoid stacked overlays. */
  private dedupe(regions: RedactRegion[]): RedactRegion[] {
    const seen = new Set<string>();
    const out: RedactRegion[] = [];
    for (const g of regions) {
      const key = `${g.pageIndex}:${g.x.toFixed(1)}:${g.y.toFixed(1)}:${g.width.toFixed(1)}:${g.height.toFixed(1)}`;
      if (seen.has(key)) continue;
      seen.add(key);
      out.push(g);
    }
    return out;
  }

  /**
   * Translated copy for the current error, via the shared copy map. The primary
   * detail is localised; the server's English sentence comes back as
   * `technical` and is shown as a secondary line rather than in its place.
   */
  get errorCopy(): ResolvedErrorCopy | null {
    const e = this.error();
    return e ? resolveErrorCopy(e, (k, params) => this.transloco.translate(k, params)) : null;
  }
}
