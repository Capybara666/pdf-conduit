import { NgClass } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoModule } from '@jsverse/transloco';

import { ApiError, PiiReport } from '../../core/api.models';
import { ApiService } from '../../core/api.service';
import { errorCopyKeys } from '../../core/error-copy';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
import { SpinnerComponent } from '../../shared/spinner/spinner.component';

/** The six GDPR categories, ordered high-risk first; `high` drives the emphasis styling. */
const CATEGORY_ORDER: readonly { key: string; high: boolean }[] = [
  { key: 'FINANCIAL', high: true },
  { key: 'NATIONAL_ID', high: true },
  { key: 'SPECIAL_CATEGORY', high: true },
  { key: 'CONTACT', high: false },
  { key: 'ONLINE_IDENTIFIER', high: false },
  { key: 'IDENTIFIER', high: false },
];

/** A category card: enum key, distinct-finding count, and whether it's a higher-risk category. */
interface CategoryView {
  key: string;
  count: number;
  high: boolean;
}

/**
 * GDPR / PII scanner report page. Upload a PDF (or image / office doc), scan it
 * entirely in memory on the server, and present a privacy-first report: an
 * overall risk badge, per-category cards, and a masked findings table. Nothing
 * is stored; only masked samples ever leave the server.
 */
@Component({
  selector: 'app-gdpr-scan-page',
  standalone: true,
  imports: [
    NgClass,
    RouterLink,
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

  protected readonly file = signal<File | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal<ApiError | null>(null);
  protected readonly report = signal<PiiReport | null>(null);

  /** Active category filter for the findings table (null = show all). */
  protected readonly filter = signal<string | null>(null);

  /** Lower-cased risk (`none`/`low`/`medium`/`high`) for styling + copy keys. */
  protected readonly riskKey = computed(() => (this.report()?.risk ?? 'NONE').toLowerCase());

  /** All six categories with their counts, high-risk first (0-count cards still shown). */
  protected readonly categories = computed<CategoryView[]>(() => {
    const counts = this.report()?.countsByCategory ?? {};
    return CATEGORY_ORDER.map((c) => ({ key: c.key, high: c.high, count: counts[c.key] ?? 0 }));
  });

  /** Distinct category keys actually present, high-risk first — for the filter chips. */
  protected readonly presentCategories = computed<string[]>(() =>
    this.categories()
      .filter((c) => c.count > 0)
      .map((c) => c.key),
  );

  /** Findings honouring the active category filter. */
  protected readonly visibleFindings = computed(() => {
    const r = this.report();
    if (!r) return [];
    const f = this.filter();
    return f ? r.findings.filter((x) => x.category === f) : r.findings;
  });

  onFile(f: File | null): void {
    this.file.set(f);
    this.error.set(null);
    this.report.set(null);
    this.filter.set(null);
  }

  scan(): void {
    const f = this.file();
    if (!f || this.loading()) return;
    this.loading.set(true);
    this.error.set(null);
    this.report.set(null);
    this.filter.set(null);

    const fd = new FormData();
    fd.append('file', f, f.name);
    this.api.gdprScan(fd).subscribe({
      next: (report) => {
        this.report.set(report);
        this.loading.set(false);
      },
      error: (e) => {
        this.error.set(e instanceof ApiError ? e : new ApiError('unknown', String(e), 0));
        this.loading.set(false);
      },
    });
  }

  setFilter(key: string | null): void {
    this.filter.set(this.filter() === key ? null : key);
  }

  /** Translation keys for the current error (title/detail/hint), via the shared copy map. */
  get errorKeys(): ReturnType<typeof errorCopyKeys> | null {
    const e = this.error();
    return e ? errorCopyKeys(e) : null;
  }
}
