import { Injectable, inject, signal } from '@angular/core';
import { of } from 'rxjs';
import { catchError } from 'rxjs/operators';

import { ApiService } from './api.service';
import { CapabilitiesInfo, OperationInfo } from './api.models';
import { environment } from '../../environments/environment';

/** Bytes in a megabyte, the unit the drop zone's cap and copy are expressed in. */
const BYTES_PER_MB = 1024 * 1024;

/**
 * Server capability state, fetched once at startup (first injection — the
 * sidebar injects it, so the fetch happens as the shell renders):
 *
 * - `GET /api/operations` → which catalog entries carry `available: false`
 *   (today only `ocr` on servers with OCR disabled) so the sidebar can hide
 *   dead pages;
 * - `GET /api/capabilities` → `officeEnabled` (To PDF drops office inputs when
 *   off), `ocrLanguages` (the OCR page's language picker) and the two upload
 *   ceilings the drop zone guards with (`maxFileSizeBytes`,
 *   `maxFilesPerRequest`).
 *
 * FAIL-OPEN by design: every signal defaults to "everything available", and a
 * failed fetch leaves the defaults untouched — the UI must behave exactly as
 * today (show all operations, full accepted-types copy, free-text languages)
 * when the backend is unreachable or predates these fields. Never blank the
 * sidebar on error.
 *
 * The upload caps are the one exception to "fail open": they fall back to the
 * `environment` values rather than to "no limit", because they exist to stop a
 * doomed upload. The server's number always wins once it arrives — the
 * `environment` copy is only what the very first paint has to work with, and
 * the reason those constants may not be trusted as the source of truth.
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

  /**
   * Largest single file the server will accept, in MB — what the drop zone
   * refuses above. Seeded from `environment.maxUploadMb` for the first paint,
   * replaced by the server's advertised `maxFileSizeBytes` when it lands.
   */
  readonly maxUploadMb = signal(environment.maxUploadMb);

  /**
   * Files the server accepts per request — what the drop zone caps the
   * selection at. Seeded from `environment.maxFilesPerRequest`, replaced by the
   * server's advertised `maxFilesPerRequest` when it lands.
   */
  readonly maxFilesPerRequest = signal(environment.maxFilesPerRequest);

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
        const mb = toMb(caps.maxFileSizeBytes);
        if (mb !== null) this.maxUploadMb.set(mb);
        if (isPositiveCount(caps.maxFilesPerRequest)) {
          this.maxFilesPerRequest.set(caps.maxFilesPerRequest);
        }
      });
  }

  /** Whether the sidebar should show the nav item with this operation id. */
  isAvailable(opId: string): boolean {
    return !this.unavailable().has(opId);
  }
}

/**
 * An advertised byte cap as whole MB, or `null` when the server did not send a
 * usable one (an older backend, or a garbled body — keep the fallback).
 * Rounded DOWN so the client never promises more than the server accepts; a cap
 * below 1 MB keeps two decimals rather than collapsing to 0, which the drop
 * zone would read as "no limit".
 */
function toMb(bytes: unknown): number | null {
  if (typeof bytes !== 'number' || !Number.isFinite(bytes) || bytes <= 0) return null;
  const mb = bytes / BYTES_PER_MB;
  return mb >= 1 ? Math.floor(mb) : Math.max(Math.floor(mb * 100) / 100, 0.01);
}

/** True for an advertised file count we can actually cap with. */
function isPositiveCount(count: unknown): count is number {
  return typeof count === 'number' && Number.isInteger(count) && count > 0;
}
