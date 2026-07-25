/**
 * Development environment.
 *
 * `apiBase` is empty so requests hit relative `/api/*`; the Angular dev-server
 * proxy (proxy.conf.json) forwards `/api` to http://localhost:8080.
 *
 * The upload caps mirror the RELAXED backend dev presets
 * (`application-local.yml` / `application-dev.yml`: `free-max-file-size: 1GB`,
 * `free-max-files: 500`) so a local box never trips a client-side guard that
 * the backend it talks to would not have tripped.
 */
export const environment = {
  production: false,
  apiBase: '',
  /** Client-side upload cap (MB). Mirrors the dev `free-max-file-size: 1GB`. */
  maxUploadMb: 1024,
  /** Files accepted per request. Mirrors the dev `free-max-files: 500`. */
  maxFilesPerRequest: 500,
};
