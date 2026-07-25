import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';

import { ApiError, RunResult } from './api.models';
import { ApiService } from './api.service';
import { errorCopyKeys } from './error-copy';
import { QuotaService } from './quota.service';
import { ToastService } from './toast.service';
import { TRANSLOCO_TESTING_PROVIDERS, translocoTesting } from '../testing/transloco-testing';

/**
 * `ApiService` is the single funnel every page's failure copy flows through, so
 * these specs pin the parsing surface rather than the transport: which
 * `error-copy` code a status maps to, whether a non-JSON body is fit to show a
 * user, how `Content-Disposition` becomes a download name, and which response
 * headers are allowed to move the quota chip.
 */
describe('ApiService', () => {
  let api: ApiService;
  let http: HttpTestingController;
  let quota: QuotaService;
  let toasts: ToastService;
  let transloco: TranslocoService;

  /** A 502/504 body as nginx actually sends it. */
  const NGINX_HTML =
    '<html>\r\n<head><title>504 Gateway Time-out</title></head>\r\n<body>\r\n' +
    '<center><h1>504 Gateway Time-out</h1></center>\r\n<hr><center>nginx</center>\r\n</body>\r\n</html>\r\n';

  beforeEach(() => {
    // QuotaService rehydrates from localStorage in its field initialiser, so a
    // previous spec's snapshot would otherwise leak into this one.
    localStorage.clear();
    TestBed.configureTestingModule({
      imports: [translocoTesting()],
      providers: [provideHttpClient(), provideHttpClientTesting(), TRANSLOCO_TESTING_PROVIDERS],
    });
    api = TestBed.inject(ApiService);
    http = TestBed.inject(HttpTestingController);
    quota = TestBed.inject(QuotaService);
    toasts = TestBed.inject(ToastService);
    transloco = TestBed.inject(TranslocoService);
  });

  afterEach(() => {
    http.verify();
    localStorage.clear();
  });

  // --- helpers ------------------------------------------------------------

  /** Fire a binary operation and resolve with the `ApiError` it throws. */
  function failing(
    respond: (req: import('@angular/common/http/testing').TestRequest) => void,
    endpoint = 'merge',
  ): Promise<ApiError> {
    const captured = new Promise<ApiError>((resolve, reject) => {
      api.runOperation(endpoint, new FormData()).subscribe({
        next: () => reject(new Error('expected the request to fail')),
        error: (e) => resolve(e as ApiError),
      });
    });
    respond(http.expectOne(`/api/${endpoint}`));
    return captured;
  }

  /** Fire a binary operation and resolve with the successful `RunResult`. */
  function succeeding(
    respond: (req: import('@angular/common/http/testing').TestRequest) => void,
    endpoint = 'merge',
  ): Promise<RunResult> {
    const captured = new Promise<RunResult>((resolve, reject) => {
      api.runOperation(endpoint, new FormData()).subscribe({ next: resolve, error: reject });
    });
    respond(http.expectOne(`/api/${endpoint}`));
    return captured;
  }

  function blob(text: string, type = 'application/octet-stream'): Blob {
    return new Blob([text], { type });
  }

  /** The detail line the result panel would actually render for this error. */
  function renderedDetail(error: ApiError): string {
    const keys = errorCopyKeys(error);
    return keys.detailText || (keys.detailKey ? transloco.translate(keys.detailKey) : '');
  }

  // --- codeForStatus ------------------------------------------------------

  describe('status → error code mapping', () => {
    // A body that is not our `{code,error}` JSON forces the status mapping to
    // decide the code (an empty body keeps the detail line out of the way).
    const cases: ReadonlyArray<[number, string]> = [
      [400, 'bad_request'],
      [408, 'processing_timeout'],
      [413, 'too_large'],
      [415, 'office_disabled'],
      [422, 'operation_failed'],
      [429, 'rate_limited'],
      [500, 'internal_error'],
      [502, 'server_busy'],
      [503, 'server_busy'],
      [504, 'processing_timeout'],
    ];

    for (const [status, code] of cases) {
      it(`maps ${status} → ${code}`, async () => {
        const err = await failing((req) =>
          req.flush(blob(''), { status, statusText: `status ${status}` }),
        );
        expect(err.code).withContext(`HTTP ${status}`).toBe(code);
        expect(err.status).toBe(status);
      });
    }

    it('maps a transport failure to network_error with status 0', async () => {
      const err = await failing((req) => req.error(new ProgressEvent('error')));
      expect(err.code).toBe('network_error');
      expect(err.status).toBe(0);
    });

    it('falls back to the generic code for an unmapped status', async () => {
      const err = await failing((req) =>
        req.flush(blob(''), { status: 418, statusText: "I'm a teapot" }),
      );
      expect(err.code).toBe('error');
      // No builder for 'error' → the generic, localised copy carries the panel.
      expect(errorCopyKeys(err).titleKey).toBe('errors.generic.title');
      expect(renderedDetail(err)).toBe('The operation could not be completed.');
    });

    it('lets an explicit server code override the status mapping', async () => {
      const err = await failing(
        (req) =>
          req.flush(blob(JSON.stringify({ code: 'repair_failed', error: 'Beyond recovery.' })), {
            status: 422,
            statusText: 'Unprocessable Entity',
          }),
        'repair',
      );
      expect(err.code).toBe('repair_failed');
      expect(err.message).toBe('Beyond recovery.');
      expect(renderedDetail(err)).toBe('Beyond recovery.');
    });

    it('gives ocr_disabled its own copy rather than the office-conversion 415', async () => {
      // Both are 415, but they name different missing dependencies. Without a
      // dedicated builder the shared 415 fallback would tell an OCR user to
      // blame LibreOffice.
      const err = await failing(
        (req) =>
          req.flush(blob(JSON.stringify({ code: 'ocr_disabled', error: 'OCR is not installed.' })), {
            status: 415,
            statusText: 'Unsupported Media Type',
          }),
        'ocr',
      );
      expect(err.code).toBe('ocr_disabled');
      const copy = errorCopyKeys(err);
      expect(copy.titleKey).toBe('errors.ocr_disabled.title');
      expect(copy.hintKey).toBe('errors.ocr_disabled.hint');
      expect(transloco.translate(copy.titleKey)).toBe('OCR is unavailable');
    });
  });

  // --- non-JSON error bodies ---------------------------------------------

  describe('non-JSON error bodies', () => {
    it('never shows an nginx HTML error page as the detail line (502)', async () => {
      const err = await failing((req) =>
        req.flush(blob(NGINX_HTML, 'text/html'), { status: 502, statusText: 'Bad Gateway' }),
      );

      expect(err.code).toBe('server_busy');
      expect(err.message).toBe('');
      expect(err.message).not.toContain('<html');
      // The user sees the localised capacity copy, not markup.
      expect(renderedDetail(err)).toBe('The server is handling a lot of requests.');
    });

    it('never shows an nginx HTML error page as the detail line (504)', async () => {
      const err = await failing((req) =>
        req.flush(blob(NGINX_HTML, 'text/html'), { status: 504, statusText: 'Gateway Time-out' }),
      );

      expect(err.code).toBe('processing_timeout');
      expect(renderedDetail(err)).toBe('The operation timed out before it finished.');
      expect(renderedDetail(err)).not.toContain('<');
    });

    it('keeps a short plain-text server message as the detail line', async () => {
      const err = await failing((req) =>
        req.flush(blob('Page range 12-40 exceeds the document length.'), {
          status: 400,
          statusText: 'Bad Request',
        }),
      );
      expect(err.code).toBe('bad_request');
      expect(renderedDetail(err)).toBe('Page range 12-40 exceeds the document length.');
    });

    it('drops an implausibly long plain-text body in favour of localised copy', async () => {
      const err = await failing((req) =>
        req.flush(blob('x'.repeat(5000)), { status: 500, statusText: 'Internal Server Error' }),
      );
      expect(err.message).toBe('');
      expect(renderedDetail(err)).toBe('The operation could not be completed.');
    });

    it('drops an HTML body even when it is short and leading-whitespace padded', async () => {
      const err = await failing((req) =>
        req.flush(blob('\n  <h1>503</h1>', 'text/html'), {
          status: 503,
          statusText: 'Service Unavailable',
        }),
      );
      expect(err.message).toBe('');
      expect(renderedDetail(err)).toBe('The server is handling a lot of requests.');
    });
  });

  // --- filenameFrom -------------------------------------------------------

  describe('download filename', () => {
    function nameFor(disposition: string | null, contentType = 'application/pdf'): Promise<string> {
      const headers: Record<string, string> = { 'Content-Type': contentType };
      if (disposition !== null) {
        headers['Content-Disposition'] = disposition;
      }
      return succeeding((req) => req.flush(blob('%PDF-1.7', contentType), { headers })).then(
        (r) => r.filename,
      );
    }

    it('reads a plain quoted filename', async () => {
      expect(await nameFor('attachment; filename="merged.pdf"')).toBe('merged.pdf');
    });

    it('reads an unquoted filename', async () => {
      expect(await nameFor('attachment; filename=merged_compressed.pdf')).toBe(
        'merged_compressed.pdf',
      );
    });

    it('prefers the RFC 5987 filename* over the ASCII fallback', async () => {
      // Spring writes both: an ASCII-transliterated `filename` and the real
      // UTF-8 name in `filename*`. The non-ASCII one must win.
      const name = await nameFor(
        `attachment; filename="raport.pdf"; filename*=UTF-8''%C5%BCo%C5%82%C4%87-raport.pdf`,
      );
      expect(name).toBe('żołć-raport.pdf');
    });

    it('percent-decodes a CJK filename*', async () => {
      expect(await nameFor(`attachment; filename*=UTF-8''%E6%96%87%E4%BB%B6.pdf`)).toBe('文件.pdf');
    });

    it('falls back to the plain filename when filename* is undecodable', async () => {
      expect(await nameFor(`attachment; filename="safe.pdf"; filename*=UTF-8''%E0%A4%A`)).toBe(
        'safe.pdf',
      );
    });

    it('derives a name from the content type when the header is absent', async () => {
      expect(await nameFor(null, 'application/zip')).toBe('result.zip');
      expect(await nameFor(null, 'application/pdf')).toBe('result.pdf');
      expect(await nameFor(null, 'image/png')).toBe('result.png');
      expect(await nameFor(null, 'text/plain')).toBe('result.txt');
      expect(await nameFor(null, 'application/octet-stream')).toBe('result');
    });
  });

  // --- quota / rate-limit headers ----------------------------------------

  describe('quota and rate-limit headers', () => {
    it('applies both quota and rate-limit headers on success', async () => {
      await succeeding((req) =>
        req.flush(blob('%PDF-1.7', 'application/pdf'), {
          headers: {
            'X-Quota-Limit': '60',
            'X-Quota-Remaining': '41',
            'X-Quota-Reset': '1900000000',
            'X-RateLimit-Limit': '40',
            'X-RateLimit-Remaining': '37',
          },
        }),
      );

      expect(quota.limit()).toBe(60);
      expect(quota.remaining()).toBe(41);
      expect(quota.snapshot()?.quotaReset).toBe(1900000000);
      expect(quota.snapshot()?.rateLimit).toBe(40);
      expect(quota.snapshot()?.rateRemaining).toBe(37);
      expect(quota.exhausted()).toBe(false);
    });

    it('ignores X-Quota-* on a rejected request but still applies X-RateLimit-*', async () => {
      // The backend writes X-Quota-Remaining optimistically in preHandle, yet
      // only charges quota on a 2xx — trusting it here would make the chip drop
      // a credit the user never spent.
      const err = await failing((req) =>
        req.flush(blob(JSON.stringify({ code: 'operation_failed', error: 'Damaged PDF.' })), {
          status: 422,
          statusText: 'Unprocessable Entity',
          headers: {
            'X-Quota-Limit': '60',
            'X-Quota-Remaining': '12',
            'X-RateLimit-Limit': '40',
            'X-RateLimit-Remaining': '9',
          },
        }),
      );

      expect(err.code).toBe('operation_failed');
      expect(quota.remaining()).withContext('quota must be untouched').toBeNull();
      expect(quota.limit()).toBeNull();
      expect(quota.snapshot()?.rateRemaining).toBe(9);
      expect(quota.snapshot()?.rateLimit).toBe(40);
    });

    it('trusts X-Quota-* on an authoritative quota_exceeded response', async () => {
      const err = await failing((req) =>
        req.flush(blob(JSON.stringify({ code: 'quota_exceeded', error: 'Daily limit reached.' })), {
          status: 429,
          statusText: 'Too Many Requests',
          headers: { 'X-Quota-Limit': '60', 'X-Quota-Remaining': '0' },
        }),
      );

      expect(err.code).toBe('quota_exceeded');
      expect(quota.remaining()).toBe(0);
      expect(quota.exhausted()).toBe(true);
    });

    it('parses Retry-After into the error and the retry hint', async () => {
      const err = await failing((req) =>
        req.flush(blob(JSON.stringify({ code: 'rate_limited', error: 'Slow down.' })), {
          status: 429,
          statusText: 'Too Many Requests',
          headers: { 'Retry-After': '30' },
        }),
      );

      expect(err.retryAfter).toBe(30);
      const copy = errorCopyKeys(err);
      expect(copy.hintKey).toBe('errors.rate_limited.hintRetry');
      expect(transloco.translate(copy.hintKey!, copy.hintParams)).toBe(
        'Please try again in about 30 seconds.',
      );
    });

    it('ignores a non-numeric Retry-After', async () => {
      const err = await failing((req) =>
        req.flush(blob(JSON.stringify({ code: 'rate_limited', error: 'Slow down.' })), {
          status: 429,
          statusText: 'Too Many Requests',
          headers: { 'Retry-After': 'Wed, 21 Oct 2026 07:28:00 GMT' },
        }),
      );

      expect(err.retryAfter).toBeUndefined();
      expect(errorCopyKeys(err).hintKey).toBe('errors.rate_limited.hintWait');
    });

    it('raises a toast for a global condition but not for a per-file failure', async () => {
      await failing((req) =>
        req.flush(blob(JSON.stringify({ code: 'quota_exceeded', error: 'Daily limit reached.' })), {
          status: 429,
          statusText: 'Too Many Requests',
          headers: { 'X-Quota-Limit': '60', 'X-Quota-Remaining': '0' },
        }),
      );
      expect(toasts.toasts().length).toBe(1);
      expect(toasts.toasts()[0].title).toBe("You've used today's free operations");

      await failing((req) =>
        req.flush(blob(JSON.stringify({ code: 'operation_failed', error: 'Damaged PDF.' })), {
          status: 422,
          statusText: 'Unprocessable Entity',
        }),
      );
      expect(toasts.toasts().length).withContext('no extra toast for a local failure').toBe(1);
    });
  });

  // --- result headers -----------------------------------------------------

  describe('result headers', () => {
    it('parses the compression headers into the result', async () => {
      const result = await succeeding(
        (req) =>
          req.flush(blob('%PDF-1.7', 'application/pdf'), {
            headers: {
              'Content-Type': 'application/pdf',
              'X-Original-Bytes': '2400000',
              'X-Result-Bytes': '900000',
              'X-Target-Reached': 'true',
            },
          }),
        'compress',
      );

      expect(result.compression).toEqual({
        originalBytes: 2400000,
        resultBytes: 900000,
        targetReached: true,
      });
    });

    it('parses a missed target without inventing a floor', async () => {
      const result = await succeeding(
        (req) =>
          req.flush(blob('%PDF-1.7', 'application/pdf'), {
            headers: {
              'Content-Type': 'application/pdf',
              'X-Original-Bytes': '2400000',
              'X-Result-Bytes': '2100000',
              'X-Target-Reached': 'false',
            },
          }),
        'compress',
      );

      // When the target is missed the compressor has already exhausted its
      // ladder, so `resultBytes` *is* the smallest achievable size — there is no
      // separate "estimated floor" to report.
      expect(result.compression).toEqual({
        originalBytes: 2400000,
        resultBytes: 2100000,
        targetReached: false,
      });
    });

    it('ignores the retired X-Target-Feasible / X-Estimated-Floor-Bytes headers', async () => {
      // Both were fabricated (feasible duplicated targetReached, the floor
      // duplicated resultBytes) and were removed from the backend. A stale
      // deployment that still sends them must not leak them into the result.
      const result = await succeeding(
        (req) =>
          req.flush(blob('%PDF-1.7', 'application/pdf'), {
            headers: {
              'Content-Type': 'application/pdf',
              'X-Original-Bytes': '2400000',
              'X-Result-Bytes': '900000',
              'X-Target-Reached': 'true',
              'X-Target-Feasible': 'false',
              'X-Estimated-Floor-Bytes': '850000',
            },
          }),
        'compress',
      );

      expect(result.compression).toEqual({
        originalBytes: 2400000,
        resultBytes: 900000,
        targetReached: true,
      });
      expect(Object.keys(result.compression ?? {}).sort()).toEqual([
        'originalBytes',
        'resultBytes',
        'targetReached',
      ]);
    });

    it('leaves compression/repair undefined when the headers are absent', async () => {
      const result = await succeeding((req) =>
        req.flush(blob('%PDF-1.7', 'application/pdf'), {
          headers: { 'Content-Type': 'application/pdf' },
        }),
      );
      expect(result.compression).toBeUndefined();
      expect(result.repair).toBeUndefined();
      expect(result.batchFailures).toBeUndefined();
    });

    it('parses the repair outcome headers', async () => {
      const result = await succeeding(
        (req) =>
          req.flush(blob('%PDF-1.7', 'application/pdf'), {
            headers: {
              'Content-Type': 'application/pdf',
              'X-Repair-Was-Damaged': 'true',
              'X-Repair-Recovered': 'false',
            },
          }),
        'repair',
      );
      expect(result.repair).toEqual({ wasDamaged: true, recovered: false });
    });
  });

  // --- endpoint resolution ------------------------------------------------

  describe('endpoint resolution', () => {
    it('accepts a bare id, a nested path and an explicit /api path', () => {
      for (const [endpoint, url] of [
        ['merge', '/api/merge'],
        ['pipeline/run', '/api/pipeline/run'],
        ['/metadata/read', '/api/metadata/read'],
        ['/api/gdpr-scan', '/api/gdpr-scan'],
      ] as const) {
        api.runOperation(endpoint, new FormData()).subscribe({ error: () => undefined });
        http.expectOne(url).flush(blob('%PDF-1.7', 'application/pdf'));
      }
    });
  });
});
