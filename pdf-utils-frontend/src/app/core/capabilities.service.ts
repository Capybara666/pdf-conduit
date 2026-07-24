import { Injectable, inject, signal } from '@angular/core';
import { of } from 'rxjs';
import { catchError } from 'rxjs/operators';

import { ApiService } from './api.service';
import { CapabilitiesInfo, OperationInfo } from './api.models';

/**
 * Server capability state, fetched once at startup (first injection — the
 * sidebar injects it, so the fetch happens as the shell renders):
 *
 * - `GET /api/operations` → which catalog entries carry `available: false`
 *   (today only `ocr` on servers with OCR disabled) so the sidebar can hide
 *   dead pages;
 * - `GET /api/capabilities` → `officeEnabled` (To PDF drops office inputs when
 *   off) and `ocrLanguages` (the OCR page's language picker).
 *
 * FAIL-OPEN by design: every signal defaults to "everything available", and a
 * failed fetch leaves the defaults untouched — the UI must behave exactly as
 * today (show all operations, full accepted-types copy, free-text languages)
 * when the backend is unreachable or predates these fields. Never blank the
 * sidebar on error.
 */
@Injectable({ providedIn: 'root' })
export class CapabilitiesService {
  private readonly api = inject(ApiService);

  /** Operation ids the server declared unavailable (`available: false`). Empty = show everything. */
  readonly unavailable = signal<ReadonlySet<string>>(new Set());

  /** Whether office/document conversion is enabled on the server (default true = full copy). */
  readonly officeEnabled = signal(true);

  /** Tesseract language codes installed on the server; empty = fall back to free-text input. */
  readonly ocrLanguages = signal<readonly string[]>([]);

  constructor() {
    this.api
      .getOperations()
      .pipe(catchError(() => of([] as OperationInfo[])))
      .subscribe((ops) => {
        const off = new Set(
          ops.filter((op) => op.available === false && op.id).map((op) => op.id as string),
        );
        if (off.size) this.unavailable.set(off);
      });

    this.api
      .getCapabilities()
      .pipe(catchError(() => of(null)))
      .subscribe((caps: CapabilitiesInfo | null) => {
        if (!caps) return;
        this.officeEnabled.set(caps.officeEnabled !== false);
        this.ocrLanguages.set(Array.isArray(caps.ocrLanguages) ? caps.ocrLanguages : []);
      });
  }

  /** Whether the sidebar should show the nav item with this operation id. */
  isAvailable(opId: string): boolean {
    return !this.unavailable().has(opId);
  }
}
