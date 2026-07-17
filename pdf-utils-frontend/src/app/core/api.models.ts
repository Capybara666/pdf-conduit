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
 * A typed error surfaced from the API. Mirrors the backend JSON body
 * `{ code, error }`; `status` is the HTTP status code.
 */
export class ApiError extends Error {
  constructor(
    public readonly code: string,
    message: string,
    public readonly status: number,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}
