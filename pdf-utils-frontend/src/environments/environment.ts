/**
 * Production environment.
 *
 * `apiBase` is empty so all requests hit same-origin `/api/*`, which nginx
 * (see nginx.conf) proxies to the Spring Boot backend.
 *
 * The two upload caps below are FALLBACKS ONLY — the first paint happens before
 * `GET /api/capabilities` answers, and these are what the drop zone guards with
 * until it does. The server owns these limits: as soon as it advertises
 * `maxFileSizeBytes` / `maxFilesPerRequest`, those win (see
 * `core/capabilities.service.ts`). Keep them roughly aligned with the public
 * deployment (`application-prod.yml`) so the pre-response window behaves; once
 * the server answers, a stale value here cannot make the UI disagree with it.
 */
export const environment = {
  production: true,
  apiBase: '',
  /** Pre-response upload cap (MB). Public deployment: `quota.free-max-file-size: 25MB`. */
  maxUploadMb: 25,
  /** Pre-response file count. Public deployment: `quota.free-max-files: 15`. */
  maxFilesPerRequest: 15,
  /**
   * Pre-response render ceiling (DPI) for To Images / the pipeline TO_IMAGES
   * node. Same contract as the two caps above: the server owns this number
   * (`render.max-dpi`, advertised as `maxDpi`), and this is only what the first
   * paint has to draw the form with.
   */
  maxDpi: 600,
};
