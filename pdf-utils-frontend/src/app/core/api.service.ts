import { HttpClient, HttpErrorResponse, HttpHeaders, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';
import { Observable, catchError, map, throwError } from 'rxjs';

import { environment } from '../../environments/environment';
import {
  ApiError,
  BatchPiiReport,
  CapabilitiesInfo,
  CompressionInfo,
  FormField,
  HealthStatus,
  MetadataDto,
  OperationInfo,
  PiiReport,
  RepairInfo,
  RunResult,
} from './api.models';
import { errorCopyKeys } from './error-copy';
import { QuotaService } from './quota.service';
import { ToastService } from './toast.service';
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
  private readonly quota = inject(QuotaService);
  private readonly toasts = inject(ToastService);
  private readonly transloco = inject(TranslocoService);
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

  /** `GET /api/capabilities` → server capability flags (office/OCR + installed OCR languages). */
  getCapabilities(): Observable<CapabilitiesInfo> {
    return this.http
      .get<CapabilitiesInfo>(`${this.base}/capabilities`)
      .pipe(catchError((err) => this.toApiError(err)));
  }

  /** `POST /api/metadata/read` → document info JSON. */
  readMetadata(formData: FormData): Observable<MetadataDto> {
    return this.http
      .post<MetadataDto>(`${this.base}/metadata/read`, formData)
      .pipe(catchError((err) => this.toApiError(err)));
  }

  // ---- JSON analysis (report, not a download) --------------------------

  /**
   * POST a multipart body to an analysis endpoint that returns a JSON report
   * (rather than a binary download). Unlike {@link runOperation} this parses the
   * body as JSON, but it still consumes quota, so the quota chip is updated from
   * the response headers just like a binary run.
   */
  analyze<T>(endpoint: string, formData: FormData): Observable<T> {
    const url = this.resolve(endpoint);
    return this.http.post<T>(url, formData, { observe: 'response' }).pipe(
      map((res) => {
        this.quota.update(res.headers);
        return res.body as T;
      }),
      catchError((err) => this.toApiError(err)),
    );
  }

  /**
   * `POST /api/form-fields` → the PDF's fillable AcroForm fields (JSON array; `[]` when the PDF has
   * no form). A cheap read-only detection (quota-exempt), so it does not touch the quota chip. The
   * collected values are filled back through {@link sign} (its `fields` param).
   */
  formFields(file: File): Observable<FormField[]> {
    const fd = new FormData();
    fd.append('file', file, file.name);
    return this.http
      .post<FormField[]>(`${this.base}/form-fields`, fd)
      .pipe(catchError((err) => this.toApiError(err)));
  }

  /** `POST /api/gdpr-scan` → GDPR / PII scan report JSON. */
  gdprScan(formData: FormData): Observable<PiiReport> {
    return this.analyze<PiiReport>('gdpr-scan', formData);
  }

  /** `POST /api/gdpr-scan-batch` → aggregated multi-file GDPR audit JSON. */
  gdprScanBatch(formData: FormData): Observable<BatchPiiReport> {
    return this.analyze<BatchPiiReport>('gdpr-scan-batch', formData);
  }

  /**
   * `POST /api/auto-redact` → the input PDF with every detected PII value permanently blacked out
   * (a binary download). One-click, free; no manual box drawing.
   */
  autoRedact(formData: FormData): Observable<RunResult> {
    return this.runOperation('auto-redact', formData);
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
  crop(formData: FormData): Observable<RunResult> {
    return this.runOperation('crop', formData);
  }
  pageMarks(formData: FormData): Observable<RunResult> {
    return this.runOperation('page-marks', formData);
  }
  redact(formData: FormData): Observable<RunResult> {
    return this.runOperation('redact', formData);
  }
  nup(formData: FormData): Observable<RunResult> {
    return this.runOperation('nup', formData);
  }
  sign(formData: FormData): Observable<RunResult> {
    return this.runOperation('sign', formData);
  }
  toImages(formData: FormData): Observable<RunResult> {
    return this.runOperation('to-images', formData);
  }
  toText(formData: FormData): Observable<RunResult> {
    return this.runOperation('to-text', formData);
  }
  ocr(formData: FormData): Observable<RunResult> {
    return this.runOperation('ocr', formData);
  }
  /**
   * `POST /api/repair` → the rebuilt PDF (single input) or a ZIP (batch). The
   * single-file response carries `X-Repair-Was-Damaged` / `X-Repair-Recovered`,
   * parsed into {@link RunResult.repair}; a 422 `repair_failed` means the file
   * could not be recovered.
   */
  repair(formData: FormData): Observable<RunResult> {
    return this.runOperation('repair', formData);
  }

  // ---- Pipeline ---------------------------------------------------------

  /** `GET /api/pipeline/kinds` → the node kinds available for the builder. */
  getPipelineKinds(): Observable<NodeKindInfo[]> {
    return this.http
      .get<NodeKindInfo[]>(`${this.base}/pipeline/kinds`)
      .pipe(catchError((err) => this.toApiError(err)));
  }

  /**
   * `POST /api/pipeline/validate` → list of validation errors (empty = OK).
   *
   * The backend reads the model from a multipart `pipeline` request-param (same
   * as `/run`, minus the file parts), so we post `FormData` — not a JSON body,
   * which the controller would silently ignore.
   */
  validatePipeline(model: PipelineModelJson): Observable<PipelineValidationError[]> {
    const fd = new FormData();
    fd.append('pipeline', JSON.stringify(model));
    return this.http
      .post<PipelineValidationError[]>(`${this.base}/pipeline/validate`, fd)
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
    this.quota.update(headers);
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
    const repair = this.repairFrom(headers);
    if (repair) {
      result.repair = repair;
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
   * Parse the repair outcome headers. Absent on batch (ZIP) responses and on any
   * server that predates them — then this returns `undefined` and the result
   * panel stays with the neutral success copy rather than guessing.
   */
  private repairFrom(headers: HttpHeaders): RepairInfo | undefined {
    const wasDamaged = headers.get('X-Repair-Was-Damaged');
    const recovered = headers.get('X-Repair-Recovered');
    if (wasDamaged == null && recovered == null) {
      return undefined;
    }
    const info: RepairInfo = {};
    if (wasDamaged != null) info.wasDamaged = wasDamaged.toLowerCase() === 'true';
    if (recovered != null) info.recovered = recovered.toLowerCase() === 'true';
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
      return throwError(() => this.finalize(new ApiError('unknown', message, 0)));
    }

    // Rate-limit headers ride along on error responses too, but the daily
    // quota is only truly spent on a 2xx (the backend writes X-Quota-Remaining
    // optimistically in preHandle). Apply only the rate-limit headers here; the
    // quota headers are applied in `finalize` for an authoritative
    // `quota_exceeded` response.
    this.quota.update(err.headers, { quota: false });
    const retryAfter = this.retryAfterFrom(err.headers);

    const status = err.status;
    const body = err.error;

    // Blob error body (from responseType: 'blob') → decode async.
    if (body instanceof Blob) {
      return new Observable<never>((subscriber) => {
        body
          .text()
          .then((text) =>
            subscriber.error(this.finalize(this.parseError(text, status, retryAfter), err.headers)),
          )
          .catch(() =>
            subscriber.error(
              this.finalize(
                new ApiError(this.codeForStatus(status), err.message, status, retryAfter),
                err.headers,
              ),
            ),
          );
      });
    }

    // JSON error body (from get<T> / post<T>).
    if (body && typeof body === 'object') {
      const code = typeof body.code === 'string' ? body.code : this.codeForStatus(status);
      const message = typeof body.error === 'string' ? body.error : err.message;
      return throwError(() => this.finalize(new ApiError(code, message, status, retryAfter), err.headers));
    }

    if (typeof body === 'string' && body.trim().startsWith('{')) {
      return throwError(() => this.finalize(this.parseError(body, status, retryAfter), err.headers));
    }

    return throwError(() =>
      this.finalize(
        new ApiError(this.codeForStatus(status), err.message, status, retryAfter),
        err.headers,
      ),
    );
  }

  private parseError(text: string, status: number, retryAfter?: number): ApiError {
    try {
      const json = JSON.parse(text);
      const code = typeof json.code === 'string' ? json.code : this.codeForStatus(status);
      const message = typeof json.error === 'string' ? json.error : `Request failed (${status})`;
      return new ApiError(code, message, status, retryAfter);
    } catch {
      // Not our `{code,error}` JSON. It is most likely a reverse-proxy error
      // page (nginx answers 502/504 with a full HTML document), so only adopt
      // the body when it still reads like a short plain-text server message —
      // otherwise hand back an empty detail and let the localised `detailKey`
      // in `error-copy.ts` provide the copy the user actually sees.
      return new ApiError(this.codeForStatus(status), this.serverDetail(text), status, retryAfter);
    }
  }

  /**
   * Longest non-JSON error body we will show verbatim. A genuine backend
   * message is one short sentence; anything longer is infrastructure output,
   * not copy meant for a user.
   */
  private static readonly MAX_DETAIL_CHARS = 200;

  /**
   * Sanitise a non-JSON error body into a detail line, or `''` when it is not
   * fit to show (HTML/XML error page, or an implausibly long blob). `''` is the
   * documented "server gave no message" value: every consumer of
   * `ErrorCopyKeys.detailText` falls back to `detailKey` on a falsy value.
   */
  private serverDetail(text: string): string {
    if (!text || /^\s*</.test(text)) {
      return '';
    }
    const trimmed = text.trim();
    return trimmed.length > 0 && trimmed.length <= ApiService.MAX_DETAIL_CHARS ? trimmed : '';
  }

  private retryAfterFrom(headers: HttpHeaders): number | undefined {
    const raw = headers.get('Retry-After');
    if (raw == null || raw === '') return undefined;
    const num = Number(raw);
    return Number.isFinite(num) && num >= 0 ? num : undefined;
  }

  /**
   * Side-effect on the final error: surface global/account conditions (quota,
   * rate-limit, capacity) as a toast so the nudge shows even when a page has no
   * visible result panel. Returns the error unchanged for the throwError chain.
   */
  private finalize(error: ApiError, headers?: HttpHeaders): ApiError {
    // A `quota_exceeded` (429, 0 remaining) response is authoritative about the
    // daily quota, so trust its X-Quota-* headers and update the chip. Every
    // other error left the quota untouched (see `toApiError`).
    if (headers && error.code === 'quota_exceeded') {
      this.quota.update(headers, { quota: true });
    }
    const copy = errorCopyKeys(error);
    if (copy.global) {
      const t = (key?: string, params?: Record<string, unknown>) =>
        key ? this.transloco.translate(key, params) : undefined;
      const detail = copy.detailText || t(copy.detailKey);
      const hint = t(copy.hintKey, copy.hintParams);
      this.toasts.show({
        kind: error.code === 'quota_exceeded' ? 'warning' : 'info',
        title: t(copy.titleKey)!,
        message: hint ?? detail,
        action: copy.proLink
          ? { label: this.transloco.translate('result.seeProPlansShort'), link: ['/'], fragment: 'pro' }
          : undefined,
      });
    }
    return error;
  }

  /**
   * Map an HTTP status to an `error-copy.ts` builder key.
   *
   * The 408/502/504 rows matter in production even though the backend never
   * emits them: nginx does. A redeploy (backend restarting) answers 502 and an
   * operation past `proxy_read_timeout` answers 504 — both with an HTML error
   * page. Without a mapping they fell through to `'error'`, which has no
   * builder, so the generic copy showed the raw page body as its detail line.
   */
  private codeForStatus(status: number): string {
    switch (status) {
      case 0:
        return 'network_error';
      case 400:
        return 'bad_request';
      case 408:
        return 'processing_timeout';
      case 413:
        return 'too_large';
      case 415:
        return 'office_disabled';
      case 422:
        return 'operation_failed';
      case 429:
        return 'rate_limited';
      case 500:
        return 'internal_error';
      case 502:
        return 'server_busy';
      case 503:
        return 'server_busy';
      case 504:
        return 'processing_timeout';
      default:
        return 'error';
    }
  }
}
