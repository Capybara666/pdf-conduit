/**
 * Production environment.
 *
 * `apiBase` is empty so all requests hit same-origin `/api/*`, which nginx
 * (see nginx.conf) proxies to the Spring Boot backend.
 *
 * The two upload caps below MIRROR the backend free tier and must be kept in
 * sync with `pdf-utils-web/src/main/resources/application-prod.yml`
 * (`pdfconduit.web.quota.free-max-file-size` / `free-max-files`). Too low and
 * we refuse work the service would happily do; too high and the user waits out
 * a doomed upload only to get a 413.
 */
export const environment = {
  production: true,
  apiBase: '',
  /** Client-side upload cap (MB). Mirrors `quota.free-max-file-size: 25MB`. */
  maxUploadMb: 25,
  /** Files accepted per request. Mirrors `quota.free-max-files: 15`. */
  maxFilesPerRequest: 15,
};
