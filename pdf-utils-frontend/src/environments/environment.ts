/**
 * Production environment.
 *
 * `apiBase` is empty so all requests hit same-origin `/api/*`, which nginx
 * (see nginx.conf) proxies to the Spring Boot backend.
 */
export const environment = {
  production: true,
  apiBase: '',
};
