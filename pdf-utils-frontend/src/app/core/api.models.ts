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
 * union. `available` is `false` only for operations this server cannot run
 * (today just `ocr` when OCR is disabled); absent/`true` means available.
 */
export type OperationInfo = Omit<Schemas['OperationInfo'], 'cardinality'> & {
  cardinality?: Cardinality;
};

/**
 * Server capability flags from `GET /api/capabilities`. Mirrors the backend
 * `CapabilitiesInfo`; the schema marks every field optional, here they are
 * required — the backend always sends all of them. `ocrLanguages` lists the
 * Tesseract language codes installed on the server (empty when OCR is disabled
 * or Tesseract is absent).
 */
export type CapabilitiesInfo = Required<Schemas['CapabilitiesInfo']>;

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
  /** Present for single-file repair responses; parsed from `X-Repair-*` headers. */
  repair?: RepairInfo;
  /** Comma/semicolon-separated batch failures from `X-Batch-Failures`, if any. */
  batchFailures?: string;
}

/**
 * Parsed compress response headers (`X-Original-Bytes`, `X-Result-Bytes`,
 * `X-Target-Reached`). That is everything the backend knows: when
 * `targetReached` is false the compressor has already exhausted its ladder, so
 * `resultBytes` is the smallest size it could produce for this file.
 */
export interface CompressionInfo {
  originalBytes?: number;
  resultBytes?: number;
  targetReached?: boolean;
}

/**
 * Parsed repair response headers (`X-Repair-Was-Damaged`, `X-Repair-Recovered`),
 * sent only for a single-file repair run. Both fields are optional: a server that
 * predates the headers (or a batch/ZIP response) simply omits them, and the UI
 * then shows the plain success state without claiming anything about the file.
 */
export interface RepairInfo {
  /** Whether the input was actually damaged (false = it was already well-formed). */
  wasDamaged?: boolean;
  /** Whether the damage could be recovered. */
  recovered?: boolean;
}

/** Document metadata from `POST /api/metadata/read`. Mirrors backend `MetadataDto`. */
export type MetadataDto = Schemas['MetadataDto'];

/**
 * One enumerated AcroForm field from `POST /api/form-fields` (JSON, not a download).
 * Mirrors the backend `FormFieldDto`; `name` is re-required and `type` re-narrowed
 * from `string` to the stable UI tag union. `options` is present only for `choice`
 * (combo/list values) or `radio` (on-values); `value` is the current value.
 * `fillable` is true only for a non-read-only text/checkbox/radio/choice field — the UI must
 * not render buttons, signatures or read-only fields as fillable inputs, nor submit values for them.
 */
export type FormField = Omit<Schemas['FormFieldDto'], 'name' | 'type'> & {
  name: string;
  type: 'text' | 'checkbox' | 'radio' | 'choice' | 'signature' | 'button' | 'other';
};

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
