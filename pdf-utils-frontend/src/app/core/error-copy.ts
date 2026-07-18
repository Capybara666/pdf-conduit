import { ApiError } from './api.models';

/**
 * i18n keys (plus resolved server text and params) for presenting an API error.
 * The actual strings live in the `errors.*` locale dictionaries; a component
 * resolves these keys through `TranslocoService`. `detailText`, when present, is
 * the raw server message and should be shown verbatim in preference to
 * `detailKey`.
 */
export interface ErrorCopyKeys {
  /** i18n key for the short, human headline. */
  titleKey: string;
  /** Raw server message to show as the detail line, if the server gave one. */
  detailText?: string;
  /** Fallback i18n key for the detail line when there's no server message. */
  detailKey?: string;
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
  operation_failed: (e) => ({
    titleKey: 'errors.operation_failed.title',
    detailText: e.message,
    detailKey: 'errors.operation_failed.detail',
    hintKey: 'errors.operation_failed.hint',
  }),
  bad_request: (e) => ({
    titleKey: 'errors.bad_request.title',
    detailText: e.message,
    detailKey: 'errors.bad_request.detail',
  }),
  // A 400 with a specific, descriptive server message; reuse the bad_request copy.
  invalid_page_range: (e) => ({
    titleKey: 'errors.bad_request.title',
    detailText: e.message,
    detailKey: 'errors.bad_request.detail',
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
  };
}
