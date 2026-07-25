import { ApiError } from './api.models';

/**
 * i18n keys (plus the raw server text and params) for presenting an API error.
 * The actual strings live in the `errors.*` locale dictionaries; a component
 * resolves these keys through `TranslocoService` — in practice via
 * {@link resolveErrorCopy}, which also applies the precedence rule below.
 *
 * **Precedence.** Every backend message is English-only, so a code that has
 * real localised copy shows that copy as the primary detail and demotes the
 * server sentence to a secondary technical line ({@link ResolvedErrorCopy}).
 * The exceptions are the codes whose localised copy is a placeholder and whose
 * server sentence IS the information (which page range, which missing
 * parameter, which file) — those set {@link serverTextPrimary}.
 */
export interface ErrorCopyKeys {
  /** i18n key for the short, human headline. */
  titleKey: string;
  /** Raw (English) server message, when the server gave one. */
  detailText?: string;
  /** i18n key for the localised detail line. */
  detailKey?: string;
  /**
   * When set, {@link detailText} leads and the localised {@link detailKey} is
   * only the fallback for an empty server message. Reserved for codes whose
   * generic copy would tell the user nothing they can act on.
   */
  serverTextPrimary?: boolean;
  /** Optional i18n key for secondary guidance / upsell nudge. */
  hintKey?: string;
  /** Interpolation params for the hint (e.g. `{ seconds }` for retry copy). */
  hintParams?: Record<string, unknown>;
  /**
   * When set, the error is a global/account condition (quota, rate, capacity)
   * worth surfacing as a toast.
   */
  global?: boolean;
  /** Link to the "Pro coming soon" teaser on the home page. */
  proLink?: boolean;
}

/** Fully resolved, ready-to-render copy for one error. */
export interface ResolvedErrorCopy {
  /** Localised headline. */
  title: string;
  /** The primary detail line — localised, except for the `serverTextPrimary` codes. */
  detail: string;
  /**
   * The server's own (English) sentence, when it is not already the primary
   * line. It regularly carries the only concrete fact in the whole error — the
   * limit that was hit, the page that was rejected, the file that failed — so
   * it is always rendered, just as a smaller secondary/technical line.
   */
  technical?: string;
  /** Localised guidance, when the code has any. */
  hint?: string;
  /** Link to the "Pro coming soon" teaser on the home page. */
  proLink?: boolean;
  /** Whether this is a global/account condition worth a toast. */
  global?: boolean;
}

/** Minimal translate signature (matches `TranslocoService.translate`). */
export type TranslateFn = (key: string, params?: Record<string, unknown>) => string;

const BUILDERS: Record<string, (e: ApiError) => ErrorCopyKeys> = {
  quota_exceeded: (e) => ({
    titleKey: 'errors.quota_exceeded.title',
    detailText: e.message,
    detailKey: 'errors.quota_exceeded.detail',
    hintKey: 'errors.quota_exceeded.hint',
    global: true,
    proLink: true,
  }),
  rate_limited: (e) => ({
    titleKey: 'errors.rate_limited.title',
    detailText: e.message,
    detailKey: 'errors.rate_limited.detail',
    hintKey: e.retryAfter ? 'errors.rate_limited.hintRetry' : 'errors.rate_limited.hintWait',
    hintParams: e.retryAfter ? { seconds: e.retryAfter } : undefined,
    global: true,
  }),
  server_busy: (e) => ({
    titleKey: 'errors.server_busy.title',
    detailText: e.message,
    detailKey: 'errors.server_busy.detail',
    hintKey: 'errors.server_busy.hint',
    global: true,
    proLink: true,
  }),
  processing_timeout: (e) => ({
    titleKey: 'errors.processing_timeout.title',
    detailText: e.message,
    detailKey: 'errors.processing_timeout.detail',
    hintKey: 'errors.processing_timeout.hint',
  }),
  too_large: (e) => ({
    titleKey: 'errors.too_large.title',
    detailText: e.message,
    detailKey: 'errors.too_large.detail',
    hintKey: 'errors.too_large.hint',
    proLink: true,
  }),
  file_too_large: (e) => ({
    titleKey: 'errors.too_large.title',
    detailText: e.message,
    detailKey: 'errors.too_large.detail',
    hintKey: 'errors.too_large.hint',
    proLink: true,
  }),
  office_disabled: (e) => ({
    titleKey: 'errors.office_disabled.title',
    detailText: e.message,
    detailKey: 'errors.office_disabled.detail',
    hintKey: 'errors.office_disabled.hint',
  }),
  // Also a 415, but a different missing dependency (tesseract, not LibreOffice).
  // Without its own entry the 415 fallback below would tell an OCR user that
  // "office conversion is unavailable", which is simply the wrong explanation.
  ocr_disabled: (e) => ({
    titleKey: 'errors.ocr_disabled.title',
    detailText: e.message,
    detailKey: 'errors.ocr_disabled.detail',
    hintKey: 'errors.ocr_disabled.hint',
  }),
  // Server sentence leads: it names the actual failure (wrong password, damaged
  // page tree, LibreOffice missing) where the localised line can only say "the
  // operation failed on this input".
  operation_failed: (e) => ({
    titleKey: 'errors.operation_failed.title',
    detailText: e.message,
    detailKey: 'errors.operation_failed.detail',
    serverTextPrimary: true,
    hintKey: 'errors.operation_failed.hint',
  }),
  // 422 from /api/repair: the file is too damaged to rebuild. Deliberately its
  // own copy — "try a valid PDF" (the generic operation_failed hint) is useless
  // advice when the whole point of the page was that the PDF is broken.
  repair_failed: (e) => ({
    titleKey: 'errors.repair_failed.title',
    detailText: e.message,
    detailKey: 'errors.repair_failed.detail',
    hintKey: 'errors.repair_failed.hint',
  }),
  // Server sentence leads: it names the offending parameter or value, and
  // "the request was not valid" on its own is not something a user can fix.
  bad_request: (e) => ({
    titleKey: 'errors.bad_request.title',
    detailText: e.message,
    detailKey: 'errors.bad_request.detail',
    serverTextPrimary: true,
  }),
  // A 400 with a specific, descriptive server message ("Invalid page range:
  // 5-2"); reuse the bad_request copy, server sentence first for the same reason.
  invalid_page_range: (e) => ({
    titleKey: 'errors.bad_request.title',
    detailText: e.message,
    detailKey: 'errors.bad_request.detail',
    serverTextPrimary: true,
  }),
  // 500 fallback; reuse the generic copy (the real cause stays server-side).
  internal_error: (e) => ({
    titleKey: 'errors.generic.title',
    detailText: e.message,
    detailKey: 'errors.generic.detail',
  }),
  network_error: (e) => ({
    titleKey: 'errors.network_error.title',
    detailText: e.message,
    detailKey: 'errors.network_error.detail',
    hintKey: 'errors.network_error.hint',
  }),
};

/** Map an ApiError to i18n presentation keys, with sensible fallbacks. */
export function errorCopyKeys(error: ApiError): ErrorCopyKeys {
  const builder = BUILDERS[error.code];
  if (builder) return builder(error);
  // 415 office-disabled sometimes surfaces without a dedicated code.
  if (error.status === 415) return BUILDERS['office_disabled'](error);
  return {
    titleKey: 'errors.generic.title',
    detailText: error.message,
    detailKey: 'errors.generic.detail',
    // A code this build has no copy for — a newer backend, most likely. Its
    // sentence is then the only description of what happened, so it leads;
    // "the operation could not be completed" stays as the fallback for an
    // empty or unusable body (an nginx HTML page, say).
    serverTextPrimary: true,
  };
}

/**
 * Resolve an error into the copy a surface actually renders.
 *
 * The rule in one line: **the localised copy is the message, the server
 * sentence is the evidence** — the user reads their own language first and the
 * English server text stays visible underneath as a technical line, except for
 * the handful of codes whose localised copy is deliberately generic
 * (`operation_failed`, `bad_request` / `invalid_page_range`, and any unknown
 * code), where the server sentence is the message.
 */
export function resolveErrorCopy(error: ApiError, translate: TranslateFn): ResolvedErrorCopy {
  const keys = errorCopyKeys(error);
  const localised = keys.detailKey ? translate(keys.detailKey) : '';
  const server = (keys.detailText ?? '').trim();
  const serverLeads = keys.serverTextPrimary === true && server !== '';
  const detail = serverLeads ? server : localised || server;
  return {
    title: translate(keys.titleKey),
    detail,
    // Never repeat the primary line as its own footnote.
    technical: server && server !== detail ? server : undefined,
    hint: keys.hintKey ? translate(keys.hintKey, keys.hintParams) : undefined,
    proLink: keys.proLink,
    global: keys.global,
  };
}
