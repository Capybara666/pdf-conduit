/**
 * Shared types for the PDF Conduit REST API (v2, base `/api`).
 * See docs/web/DESIGN.md §3 for the multipart contract.
 */

/** Health payload from `GET /api/health`. */
export interface HealthStatus {
  status: string; // "UP"
}

/** Whether an operation collapses many inputs to one (REDUCE) or maps 1:1 (MAP). */
export type Cardinality = 'MAP' | 'REDUCE';

/** Catalog entry from `GET /api/operations`. */
export interface OperationInfo {
  id: string;
  label: string;
  cardinality: Cardinality;
  multiOutput?: boolean;
}

/**
 * A successful binary download from a run: the raw bytes plus the filename
 * parsed from `Content-Disposition`, and any operation-specific headers.
 */
export interface RunResult {
  blob: Blob;
  filename: string;
  contentType: string;
  /** Present for compress responses; parsed from `X-*` headers. */
  compression?: CompressionInfo;
  /** Comma/semicolon-separated batch failures from `X-Batch-Failures`, if any. */
  batchFailures?: string;
}

/** Parsed compress response headers. */
export interface CompressionInfo {
  originalBytes?: number;
  resultBytes?: number;
  targetReached?: boolean;
}

/** Document metadata from `POST /api/metadata/read`. */
export interface MetadataDto {
  title?: string;
  author?: string;
  subject?: string;
  keywords?: string;
}

/**
 * Rate-limit / daily-quota snapshot parsed from response headers
 * (`X-RateLimit-*`, `X-Quota-*`). Every field is optional — the backend may
 * not send all of them on every response.
 */
export interface QuotaSnapshot {
  /** Requests allowed in the current rate-limit window. */
  rateLimit?: number;
  /** Requests remaining in the current rate-limit window. */
  rateRemaining?: number;
  /** Free operations allowed per day. */
  quotaLimit?: number;
  /** Free operations remaining today. */
  quotaRemaining?: number;
  /** Epoch seconds at which the daily quota resets. */
  quotaReset?: number;
}

/**
 * A typed error surfaced from the API. Mirrors the backend JSON body
 * `{ code, error }`; `status` is the HTTP status code. `retryAfter` carries the
 * `Retry-After` header (seconds) when the backend returns 429/503.
 */
export class ApiError extends Error {
  constructor(
    public readonly code: string,
    message: string,
    public readonly status: number,
    public readonly retryAfter?: number,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}
