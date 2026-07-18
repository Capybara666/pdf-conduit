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

/** GDPR risk level for a scanned document (mirrors the backend `RiskLevel`). */
export type PiiRisk = 'NONE' | 'LOW' | 'MEDIUM' | 'HIGH';

/** A single distinct piece of personal data found (masked — never the raw value). */
export interface PiiFinding {
  /** `PiiType` enum name, e.g. `EMAIL`, `IBAN`, `PESEL`. */
  type: string;
  /** `PiiCategory` enum name, e.g. `CONTACT`, `FINANCIAL`, `SPECIAL_CATEGORY`. */
  category: string;
  /** 1-based page of the first occurrence. */
  page: number;
  /** A redacted, recognisable sample that never reveals the full value. */
  maskedSample: string;
  /** How many times this exact value occurred across the document. */
  occurrences: number;
}

/** GDPR / PII scan report from `POST /api/gdpr-scan` (JSON, not a download). */
export interface PiiReport {
  totalFindings: number;
  risk: PiiRisk;
  pagesScanned: number;
  /** Distinct-finding count keyed by `PiiCategory` enum name. */
  countsByCategory: Record<string, number>;
  findings: PiiFinding[];
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
