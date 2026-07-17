/**
 * Development environment.
 *
 * `apiBase` is empty so requests hit relative `/api/*`; the Angular dev-server
 * proxy (proxy.conf.json) forwards `/api` to http://localhost:8080.
 */
export const environment = {
  production: false,
  apiBase: '',
};
