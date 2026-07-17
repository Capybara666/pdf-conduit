import { HttpClient, HttpErrorResponse, HttpHeaders, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, throwError } from 'rxjs';

import { environment } from '../../environments/environment';
import {
  ApiError,
  CompressionInfo,
  HealthStatus,
  MetadataDto,
  OperationInfo,
  RunResult,
} from './api.models';
import { NodeKindInfo, PipelineModelJson, PipelineValidationError } from './pipeline.models';

/**
 * Single entry point for talking to the PDF Conduit REST API.
 *
 * Every operation endpoint takes a multipart `FormData` body and returns a
 * binary download (`RunResult`), except metadata-read (JSON) and the two GET
 * catalog/health endpoints. Errors are normalised to `ApiError`.
 *
 * Base URL comes from `environment.apiBase` (empty in both dev and prod so the
 * dev-server proxy / nginx handle same-origin `/api`).
 */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBase}/api`;

  // ---- Catalog / health -------------------------------------------------

  /** `GET /api/health` → `{ status: "UP" }`. */
  getHealth(): Observable<HealthStatus> {
    return this.http
      .get<HealthStatus>(`${this.base}/health`)
      .pipe(catchError((err) => this.toApiError(err)));
  }

  /** `GET /api/operations` → catalog for building the UI dynamically. */
  getOperations(): Observable<OperationInfo[]> {
    return this.http
      .get<OperationInfo[]>(`${this.base}/operations`)
      .pipe(catchError((err) => this.toApiError(err)));
  }

  /** `POST /api/metadata/read` → document info JSON. */
  readMetadata(formData: FormData): Observable<MetadataDto> {
    return this.http
      .post<MetadataDto>(`${this.base}/metadata/read`, formData)
      .pipe(catchError((err) => this.toApiError(err)));
  }

  // ---- Generic binary operation ----------------------------------------

  /**
   * POST a multipart body to any operation endpoint and stream back the binary
   * result. `endpoint` may be a bare op id (`"merge"`) or an explicit path
   * (`"metadata/read"`); a leading `/api` or `/` is tolerated.
   */
  runOperation(endpoint: string, formData: FormData): Observable<RunResult> {
    const url = this.resolve(endpoint);
    return this.http
      .post(url, formData, { observe: 'response', responseType: 'blob' })
      .pipe(
        map((res) => this.toRunResult(res)),
        catchError((err) => this.toApiError(err)),
      );
  }

  // ---- Typed convenience wrappers (thin; forms build the FormData) ------

  merge(formData: FormData): Observable<RunResult> {
    return this.runOperation('merge', formData);
  }
  extract(formData: FormData): Observable<RunResult> {
    return this.runOperation('extract', formData);
  }
  compress(formData: FormData): Observable<RunResult> {
    return this.runOperation('compress', formData);
  }
  rotate(formData: FormData): Observable<RunResult> {
    return this.runOperation('rotate', formData);
  }
  arrange(formData: FormData): Observable<RunResult> {
    return this.runOperation('arrange', formData);
  }
  toPdf(formData: FormData): Observable<RunResult> {
    return this.runOperation('to-pdf', formData);
  }
  protect(formData: FormData): Observable<RunResult> {
    return this.runOperation('protect', formData);
  }
  unlock(formData: FormData): Observable<RunResult> {
    return this.runOperation('unlock', formData);
  }
  updateMetadata(formData: FormData): Observable<RunResult> {
    return this.runOperation('metadata', formData);
  }
  watermark(formData: FormData): Observable<RunResult> {
    return this.runOperation('watermark', formData);
  }
  redact(formData: FormData): Observable<RunResult> {
    return this.runOperation('redact', formData);
  }
  toImages(formData: FormData): Observable<RunResult> {
    return this.runOperation('to-images', formData);
  }
  toText(formData: FormData): Observable<RunResult> {
    return this.runOperation('to-text', formData);
  }

  // ---- Pipeline ---------------------------------------------------------

  /** `GET /api/pipeline/kinds` → the node kinds available for the builder. */
  getPipelineKinds(): Observable<NodeKindInfo[]> {
    return this.http
      .get<NodeKindInfo[]>(`${this.base}/pipeline/kinds`)
      .pipe(catchError((err) => this.toApiError(err)));
  }

  /** `POST /api/pipeline/validate` → list of validation errors (empty = OK). */
  validatePipeline(model: PipelineModelJson): Observable<PipelineValidationError[]> {
    return this.http
      .post<PipelineValidationError[]>(`${this.base}/pipeline/validate`, model)
      .pipe(catchError((err) => this.toApiError(err)));
  }

  /** `POST /api/pipeline/run` → ZIP of terminal outputs. */
  runPipeline(formData: FormData): Observable<RunResult> {
    return this.runOperation('pipeline/run', formData);
  }

  // ---- Helpers ----------------------------------------------------------

  private resolve(endpoint: string): string {
    let e = endpoint.trim();
    if (e.startsWith('/api')) {
      return `${environment.apiBase}${e}`;
    }
    e = e.replace(/^\/+/, '');
    return `${this.base}/${e}`;
  }

  private toRunResult(res: HttpResponse<Blob>): RunResult {
    const headers = res.headers;
    const blob = res.body ?? new Blob();
    const contentType = headers.get('Content-Type') ?? blob.type ?? 'application/octet-stream';
    const result: RunResult = {
      blob,
      filename: this.filenameFrom(headers, contentType),
      contentType,
    };

    const compression = this.compressionFrom(headers);
    if (compression) {
      result.compression = compression;
    }
    const batchFailures = headers.get('X-Batch-Failures');
    if (batchFailures) {
      result.batchFailures = batchFailures;
    }
    return result;
  }

  /** Parse `filename` (or `filename*`) from `Content-Disposition`. */
  private filenameFrom(headers: HttpHeaders, contentType: string): string {
    const disposition = headers.get('Content-Disposition') ?? '';

    const star = /filename\*=(?:UTF-8'')?([^;]+)/i.exec(disposition);
    if (star && star[1]) {
      try {
        return decodeURIComponent(star[1].trim().replace(/^"|"$/g, ''));
      } catch {
        // fall through to the plain form
      }
    }
    const plain = /filename="?([^";]+)"?/i.exec(disposition);
    if (plain && plain[1]) {
      return plain[1].trim();
    }
    return this.fallbackName(contentType);
  }

  private fallbackName(contentType: string): string {
    if (contentType.includes('zip')) return 'result.zip';
    if (contentType.includes('pdf')) return 'result.pdf';
    if (contentType.includes('png')) return 'result.png';
    if (contentType.includes('jpeg')) return 'result.jpg';
    if (contentType.startsWith('text/')) return 'result.txt';
    return 'result';
  }

  private compressionFrom(headers: HttpHeaders): CompressionInfo | undefined {
    const original = headers.get('X-Original-Bytes');
    const resultBytes = headers.get('X-Result-Bytes');
    const reached = headers.get('X-Target-Reached');
    if (original == null && resultBytes == null && reached == null) {
      return undefined;
    }
    const info: CompressionInfo = {};
    if (original != null) info.originalBytes = Number(original);
    if (resultBytes != null) info.resultBytes = Number(resultBytes);
    if (reached != null) info.targetReached = reached.toLowerCase() === 'true';
    return info;
  }

  /**
   * Normalise any HTTP failure into an `ApiError`. Error bodies arrive as a
   * Blob (responseType 'blob'), so decode + parse the `{ code, error }` JSON
   * asynchronously and re-emit.
   */
  private toApiError(err: unknown): Observable<never> {
    if (!(err instanceof HttpErrorResponse)) {
      const message = err instanceof Error ? err.message : 'Unexpected error';
      return throwError(() => new ApiError('unknown', message, 0));
    }

    const status = err.status;
    const body = err.error;

    // Blob error body (from responseType: 'blob') → decode async.
    if (body instanceof Blob) {
      return new Observable<never>((subscriber) => {
        body
          .text()
          .then((text) => subscriber.error(this.parseError(text, status)))
          .catch(() => subscriber.error(new ApiError('unknown', err.message, status)));
      });
    }

    // JSON error body (from get<T> / post<T>).
    if (body && typeof body === 'object') {
      const code = typeof body.code === 'string' ? body.code : this.codeForStatus(status);
      const message = typeof body.error === 'string' ? body.error : err.message;
      return throwError(() => new ApiError(code, message, status));
    }

    if (typeof body === 'string' && body.trim().startsWith('{')) {
      return throwError(() => this.parseError(body, status));
    }

    return throwError(() => new ApiError(this.codeForStatus(status), err.message, status));
  }

  private parseError(text: string, status: number): ApiError {
    try {
      const json = JSON.parse(text);
      const code = typeof json.code === 'string' ? json.code : this.codeForStatus(status);
      const message = typeof json.error === 'string' ? json.error : `Request failed (${status})`;
      return new ApiError(code, message, status);
    } catch {
      return new ApiError(this.codeForStatus(status), text || `Request failed (${status})`, status);
    }
  }

  private codeForStatus(status: number): string {
    switch (status) {
      case 0:
        return 'network_error';
      case 400:
        return 'bad_request';
      case 413:
        return 'file_too_large';
      case 422:
        return 'operation_failed';
      case 500:
        return 'internal_error';
      default:
        return 'error';
    }
  }
}
