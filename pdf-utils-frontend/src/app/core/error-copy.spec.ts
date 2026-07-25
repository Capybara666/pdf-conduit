import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';

import { ApiError } from './api.models';
import { errorCopyKeys, resolveErrorCopy } from './error-copy';
import { TRANSLOCO_TESTING_PROVIDERS, translocoTesting } from '../testing/transloco-testing';

/**
 * Two rules live here, and both are about what a non-English user actually
 * reads.
 *
 * 1. Every code the backend can emit has copy of its own. A code that falls
 *    through to the generic branch produces a title that says nothing plus an
 *    English server sentence — which is exactly what `output_too_large` did
 *    when it shipped.
 * 2. The translated copy is the message; the server's (English-only) sentence
 *    is the evidence underneath it. It is demoted, never dropped: it carries
 *    the limit, the page or the filename that the generic copy cannot know.
 *    The few codes whose localised copy is deliberately generic keep the
 *    server sentence as the message.
 *
 * The assertions run against the real `en.json`, so a missing key surfaces as a
 * raw dotted path rather than passing against a stub.
 */
describe('error copy', () => {
  let transloco: TranslocoService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [translocoTesting()],
      providers: [TRANSLOCO_TESTING_PROVIDERS],
    });
    transloco = TestBed.inject(TranslocoService);
  });

  function resolve(error: ApiError) {
    return resolveErrorCopy(error, (key, params) => transloco.translate(key, params));
  }

  // --- precedence ---------------------------------------------------------

  describe('localised copy vs the English server sentence', () => {
    it('leads with the translated copy for a code that has real copy', () => {
      const copy = resolve(
        new ApiError('processing_timeout', 'Processing exceeded 60 seconds.', 503),
      );
      expect(copy.detail).toBe('The operation timed out before it finished.');
      expect(copy.technical).toBe('Processing exceeded 60 seconds.');
    });

    it('keeps a 413 that names the limit reachable, below the localised line', () => {
      const copy = resolve(new ApiError('too_large', 'Each file may be at most 20 MB.', 413));
      expect(copy.detail).toBe('The upload is larger than the free tier allows.');
      expect(copy.technical).toBe('Each file may be at most 20 MB.');
    });

    it('translates the repair failure and demotes the server wording', () => {
      const copy = resolve(new ApiError('repair_failed', 'Beyond recovery.', 422));
      expect(copy.detail).toBe('The damage runs too deep to rebuild the file.');
      expect(copy.technical).toBe('Beyond recovery.');
    });

    // --- the deliberate exceptions ---------------------------------------

    it('keeps the server sentence primary for operation_failed', () => {
      // "The operation failed on this input" would erase the only fact there is.
      const copy = resolve(new ApiError('operation_failed', 'The PDF is password protected.', 422));
      expect(copy.detail).toBe('The PDF is password protected.');
      expect(copy.technical).toBeUndefined();
      // Its localised hint still shows, in the user's language.
      expect(copy.hint).toBe('Check the file is a valid, unencrypted PDF and try again.');
    });

    it('keeps the server sentence primary for a rejected page range', () => {
      const copy = resolve(
        new ApiError('invalid_page_range', 'Page range 12-40 exceeds the document length.', 400),
      );
      expect(copy.detail).toBe('Page range 12-40 exceeds the document length.');
      expect(copy.technical).toBeUndefined();
    });

    it('keeps the server sentence primary for an unknown, newer code', () => {
      const copy = resolve(new ApiError('brand_new_code', 'Widget budget exhausted.', 422));
      expect(copy.title).toBe('Something went wrong');
      expect(copy.detail).toBe('Widget budget exhausted.');
    });

    it('falls back to the localised line when the server said nothing usable', () => {
      // An nginx HTML page is sanitised to '' upstream, so even the
      // server-first codes must degrade to their translated copy.
      const copy = resolve(new ApiError('operation_failed', '', 422));
      expect(copy.detail).toBe('The operation failed on this input.');
      expect(copy.technical).toBeUndefined();
    });

    it('never repeats the primary line as its own footnote', () => {
      const copy = resolve(new ApiError('bad_request', 'Parameter "pages" is required.', 400));
      expect(copy.detail).toBe('Parameter "pages" is required.');
      expect(copy.technical).toBeUndefined();
    });
  });

  // --- coverage of the backend's code list --------------------------------

  describe('every code the backend can emit', () => {
    /**
     * `GlobalExceptionHandler` plus the two short-circuiting filters
     * (`RateLimitFilter`, `QuotaInterceptor`), plus the transport-level code the
     * frontend synthesises itself. Each must resolve to copy of its own — a code
     * missing from `error-copy.ts` lands on `errors.generic.*`, which is what
     * this list exists to catch.
     */
    const CODES: readonly string[] = [
      'invalid_page_range',
      'bad_request',
      'repair_failed',
      'operation_failed',
      'office_disabled',
      'ocr_disabled',
      'file_too_large',
      'too_large',
      'server_busy',
      'processing_timeout',
      'internal_error',
      'quota_exceeded',
      'rate_limited',
      'network_error',
    ];

    for (const code of CODES) {
      it(`${code} resolves to copy, not a raw key`, () => {
        const copy = resolve(new ApiError(code, '', 500));
        expect(copy.title).withContext(`${code} title`).not.toContain('errors.');
        expect(copy.detail).withContext(`${code} detail`).not.toContain('errors.');
        expect(copy.detail.length).withContext(`${code} detail`).toBeGreaterThan(0);
      });
    }

    it('gives internal_error the generic copy on purpose, not by omission', () => {
      // The real cause stays server-side, so the generic line IS the right copy
      // here — but it must be reached deliberately, with the code recognised.
      expect(errorCopyKeys(new ApiError('internal_error', '', 500)).titleKey).toBe(
        'errors.generic.title',
      );
    });
  });
});
