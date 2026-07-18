/**
 * Shared types for the PDF Conduit REST API (v2, base `/api`).
 * See docs/web/DESIGN.md §3 for the multipart contract.
 *
 * DTOs that directly mirror backend response schemas are re-exported from the
 * generated `api.gen.ts` (`npm run gen:api`, kept honest by the CI drift gate),
 * so the wire contract lives in one place. FE-only view models (RunResult,
 * CompressionInfo, QuotaSnapshot) and the `ApiError` runtime class stay
 * hand-declared. Where the OpenAPI schema widens a field (all-optional, enums
 * as bare `string`) we re-narrow it back to the shape the app relies on.
 */
import type { components } from './api.gen';

type Schemas = components['schemas'];

/**
 * Health payload from `GET /api/health`. Hand-declared: the `/health` endpoint
 * is served on the internal management port and carries no schema in the public
 * OpenAPI document, so there is nothing to re-export.
 */
export interface HealthStatus {
  status: string; // "UP"
}

/** Whether an operation collapses many inputs to one (REDUCE) or maps 1:1 (MAP). */
export type Cardinality = 'MAP' | 'REDUCE';

/**
 * Catalog entry from `GET /api/operations`. Mirrors the backend `OperationInfo`
 * schema; `cardinality` is re-narrowed from `string` to the {@link Cardinality}
 * union.
 */
export type OperationInfo = Omit<Schemas['OperationInfo'], 'cardinality'> & {
  cardinality?: Cardinality;
};

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

/** Document metadata from `POST /api/metadata/read`. Mirrors backend `MetadataDto`. */
export type MetadataDto = Schemas['MetadataDto'];

/** GDPR risk level for a scanned document (mirrors the backend `RiskLevel`). */
export type PiiRisk = 'NONE' | 'LOW' | 'MEDIUM' | 'HIGH';

/**
 * A redaction rectangle in displayed-page PDF points: top-left origin (x right,
 * y down), page rotation already applied, 0-based `pageIndex`. Mirrors the
 * backend `Region` schema (`RedactRegionDto`) and the viewer's `RegionRect` —
 * the scan returns findings' regions in this exact space so they feed straight
 * into `/api/redact`. The schema marks every field optional; here they are
 * required — the app treats a region as a fully-populated rectangle.
 */
export type RedactRegion = Required<Schemas['Region']>;

/**
 * A single distinct piece of personal data found (masked — never the raw
 * value). Mirrors backend `Finding`; `regions` is re-typed to the required
 * {@link RedactRegion}.
 *
 * `type` is a `PiiType` enum name (`EMAIL`, `IBAN`, `PESEL`, …); `category` a
 * `PiiCategory` name (`CONTACT`, `FINANCIAL`, `SPECIAL_CATEGORY`, …); `page` is
 * 1-based; `regions` holds one box per occurrence (concrete-value findings carry
 * them, `SPECIAL_CATEGORY` keyword flags come back empty).
 */
export type PiiFinding = Omit<Required<Schemas['Finding']>, 'regions'> & {
  regions: RedactRegion[];
};

/**
 * GDPR / PII scan report from `POST /api/gdpr-scan` (JSON, not a download).
 * Mirrors backend `PiiReportDto`; `risk` is re-narrowed to {@link PiiRisk},
 * `findings` to {@link PiiFinding}, and `findings` is required so the report is
 * always a complete list. `countsByCategory` is keyed by `PiiCategory` name.
 */
export type PiiReport = Omit<Required<Schemas['PiiReportDto']>, 'risk' | 'findings'> & {
  risk: PiiRisk;
  findings: PiiFinding[];
};

/**
 * One file's entry in a batch GDPR audit: its name plus its full {@link PiiReport}.
 * Mirrors backend `FileReport`; `report` re-narrowed to {@link PiiReport}.
 */
export type BatchFileReport = Omit<Required<Schemas['FileReport']>, 'report'> & {
  report: PiiReport;
};

/**
 * Aggregated GDPR compliance audit from `POST /api/gdpr-scan-batch` (JSON, not a download).
 * Mirrors backend `BatchPiiReportDto`; `highestRisk` is re-narrowed to {@link PiiRisk} and `files`
 * to the required {@link BatchFileReport}. `countsByCategory` is keyed by `PiiCategory` name, summed
 * across every scanned file.
 */
export type BatchPiiReport = Omit<Required<Schemas['BatchPiiReportDto']>, 'highestRisk' | 'files'> & {
  highestRisk: PiiRisk;
  files: BatchFileReport[];
};

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
