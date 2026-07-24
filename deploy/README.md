# Deploying PDF Conduit on a VPS (public, free SaaS)

This guide takes the PDF Conduit web stack from source to a public,
HTTPS-secured deployment on a single small VPS. TLS is automatic (Let's Encrypt
via Caddy); the box is protected by layered rate-limiting, quotas, concurrency
caps and container memory limits so a flood of uploads can't knock it over.

## Service topology

```
                          Internet
                             │  :80 / :443 (HTTPS, HTTP/3)
                    ┌────────▼────────┐
                    │      caddy      │  TLS termination + Let's Encrypt
                    │  (prod overlay) │  automatic certs, edge compression
                    └────────┬────────┘
                             │  internal compose network (http)
                    ┌────────▼────────┐
                    │    frontend     │  nginx: serves Angular SPA,
                    │   (nginx:alpine)│  security headers, edge rate-limit,
                    └────────┬────────┘  proxies /api → backend
                             │  internal compose network (http)
                    ┌────────▼────────┐
                    │     backend     │  Spring Boot, in-memory PDF work,
                    │ (temurin 21+LO) │  app rate-limit + quotas + concurrency
                    └─────────────────┘
```

- **Local dev** (`docker-compose.yml` alone): no Caddy, no TLS. The `frontend`
  nginx publishes `FRONTEND_PORT` (default 4200) directly on the host.
- **Prod** (`docker-compose.yml` + `docker-compose.prod.yml`): Caddy is the only
  service publishing host ports (80/443); the frontend port is dropped; resource
  limits and extra hardening are applied.

## Prerequisites

1. A VPS (2 GB RAM recommended for the default limits) running Linux.
2. **Docker Engine + Docker Compose v2** (`docker compose version` ≥ v2.24 —
   the prod overlay uses the `!override` merge tag).
3. A **domain name** with a DNS **A record** (and AAAA if you have IPv6) pointed
   at the VPS's public IP. TLS won't provision without this.
4. Ports **80 and 443** open in the VPS firewall/security group (Caddy needs 80
   reachable for the ACME HTTP challenge, 443 for HTTPS).

## Deploy

```bash
# 1. Get the code onto the VPS
git clone <repo-url> pdf-utils && cd pdf-utils

# 2. Create your env file and edit the MUST-CHANGE values
cp .env.example .env
```

Edit `.env` and set at minimum:

| Variable | Set to | Why |
|---|---|---|
| `DOMAIN` | `pdf.example.com` | Domain Caddy provisions HTTPS for |
| `ACME_EMAIL` | `you@example.com` | Let's Encrypt account / expiry notices |
| `PDFCONDUIT_WEB_CORS_ALLOWED_ORIGINS` | `https://pdf.example.com` | Lock CORS to your real origin |
| `PDFCONDUIT_WEB_OCR_ENABLED` | `true` | OCR is off by default in code; without this the public OCR page returns 415 |

```bash
# 3. Build images and bring the stack up (prod overlay = TLS + limits)
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
```

That's it. On first request to `https://pdf.example.com`, Caddy obtains a
certificate automatically and serves the app over HTTPS thereafter. Certs are
stored in the `caddy_data` volume and survive restarts/redeploys.

### Local trial without a domain (no TLS)

Set `DOMAIN=:80` in `.env` (the default) and bring the prod overlay up — Caddy
serves plain HTTP on port 80 with no certificate attempt. Or just use the base
compose for dev: `docker compose up --build` → `http://localhost:4200`.

## Verify it's healthy

```bash
# All services should be "healthy" (or at least "running")
docker compose -f docker-compose.yml -f docker-compose.prod.yml ps

# Backend health (from inside the network)
docker compose exec backend curl -fsS http://localhost:8080/api/health

# Public health through the edge
curl -fsS https://pdf.example.com/api/health

# Follow logs
docker compose -f docker-compose.yml -f docker-compose.prod.yml logs -f caddy backend
```

If TLS fails to provision: check DNS actually resolves to this box, that ports
80/443 are open, and Caddy's logs. To avoid hitting Let's Encrypt rate limits
while debugging, uncomment the `acme_ca` staging line in the `Caddyfile`.

## Tuning for your VPS

All knobs live in `.env`. Sizing rules of thumb:

### Memory + JVM heap (the anti-OOM pair)

The backend does all PDF work **in memory**, so RAM is the real constraint.
Two settings move together:

- `docker-compose.prod.yml` → `backend.mem_limit` / `deploy.resources.limits.memory`
  (default **1536m**) — the container's hard memory ceiling.
- `.env` → `JAVA_OPTS` (default `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError`)
  — the heap is 75% of that limit, and an OOM **exits** the JVM so Docker's
  `restart: unless-stopped` recycles it instead of thrashing.

Raise **both** together for a bigger box (e.g. 4 GB VPS → `memory: 3g`). Leave
headroom: the container also runs LibreOffice subprocesses outside the heap, so
never give the heap 100% of the limit.

Frontend and Caddy are capped at **256m / 0.5 cpu** each — plenty for static
serving and TLS.

### Rate limits, quotas, concurrency

- Edge (nginx, `pdf-utils-frontend/nginx.conf`): `limit_req` zones — `api` at
  5r/s (burst 20) and `general` at 30r/s (burst 40), plus a 50-connection
  per-IP cap, all returning **429**. Coarse flood guard.
- App (backend, `.env` `PDFCONDUIT_WEB_RATELIMIT_*` / `_QUOTA_*` /
  `_CONCURRENCY_*`): precise per-client/day/endpoint limits and, crucially,
  `MAX_IN_FLIGHT_BYTES` (default 512 MiB) — the ceiling on bytes held in memory
  across all in-flight requests. Keep it comfortably below the heap.
- `PDFCONDUIT_WEB_PROCESSING_TIMEOUT_SECONDS` / `_OFFICE_TIMEOUT_SECONDS` bound
  how long any one operation can run. If you raise the office timeout, also
  raise the nginx `/api` `proxy_read_timeout` (currently 120s) to match.

For a very small box, lower `MAX_HEAVY_OPS`, `MAX_IN_FLIGHT_BYTES`, and the
per-minute rates; for a beefier one, raise them alongside the memory limit.

## Security model — what protects the box

Defense-in-depth, outermost first:

1. **Caddy (edge)** — TLS termination, HSTS, no plaintext, edge compression.
2. **nginx (frontend)** — `server_tokens off`, full security-header set incl. a
   pdf.js-tuned Content-Security-Policy, `limit_req`/`limit_conn` flood guard
   (429), body-size cap. Forwards the real client IP (`X-Forwarded-For`/`-Proto`).
3. **Backend app limits** — per-IP rate limiting, per-client daily quotas,
   free-tier file-size/count caps, a global heavy-op concurrency cap, and an
   **in-flight-bytes** ceiling — the precise, IP-aware layer.
4. **Container caps** — `mem_limit`/`cpus`/`pids_limit` per service, so even if
   everything above is bypassed a single container can't exhaust the host.
5. **OOM-restart** — `-XX:+ExitOnOutOfMemoryError` + `restart: unless-stopped`:
   an out-of-memory backend dies and is recreated clean rather than degrading.
6. **Least privilege** — backend runs as non-root uid 10001; all services set
   `no-new-privileges`; frontend and Caddy run with a **read-only root
   filesystem** (writable paths are tmpfs / named volumes only).

### Notes / verify-in-browser

- The **CSP** (in `nginx.conf`) is tuned for Angular + pdf.js
  (`worker-src blob:`, `wasm-unsafe-eval`, `style-src 'unsafe-inline'`). It's
  "reasonable but not maximally strict" — **test PDF rendering in a browser**
  after any Angular/pdf.js upgrade and tighten if you can.
- Security headers are set **primarily at the nginx layer** (single source of
  truth); Caddy adds a minimal belt-and-braces subset.
- These files were validated with `docker compose config` (YAML/merge parse) but
  **not** built or run in this environment — do a real `up --build` on the VPS.
