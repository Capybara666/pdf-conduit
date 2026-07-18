/**
 * Production environment.
 *
 * `apiBase` is empty so all requests hit same-origin `/api/*`, which nginx
 * (see nginx.conf) proxies to the Spring Boot backend.
 */
export const environment = {
  production: true,
  apiBase: '',
  /**
   * Client-side upload cap (MB) shown in and enforced by the shared drop zone.
   * Mirror this to the backend free-tier limit when it lands.
   */
  maxUploadMb: 20,
};
