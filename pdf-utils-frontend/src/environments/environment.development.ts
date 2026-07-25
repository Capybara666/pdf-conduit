/**
 * Development environment.
 *
 * `apiBase` is empty so requests hit relative `/api/*`; the Angular dev-server
 * proxy (proxy.conf.json) forwards `/api` to http://localhost:8080.
 *
 * The upload caps are FALLBACKS ONLY, used until `GET /api/capabilities`
 * answers; the server's advertised limits then win (see
 * `core/capabilities.service.ts`). They are deliberately generous — matching the
 * relaxed dev presets (`application-local.yml` / `application-dev.yml`) — so the
 * pre-response window never refuses a file a local backend would have taken.
 * Note the dev backend's EFFECTIVE per-file cap is the 100 MB multipart ceiling,
 * not the 1 GB free-tier value; that is exactly the sort of mismatch the
 * advertised number resolves.
 */
export const environment = {
  production: false,
  apiBase: '',
  /** Pre-response upload cap (MB). Dev preset: `free-max-file-size: 1GB`. */
  maxUploadMb: 1024,
  /** Pre-response file count. Dev preset: `free-max-files: 500`. */
  maxFilesPerRequest: 500,
  /**
   * Pre-response render ceiling (DPI). The dev preset allows far more
   * (`render.max-dpi: 1200`), so this only under-promises for the handful of
   * milliseconds before `GET /api/capabilities` answers — the safe direction.
   */
  maxDpi: 600,
};
