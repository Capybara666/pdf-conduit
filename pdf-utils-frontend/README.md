# PDF Conduit — Web Frontend

Angular 18 single-page app for **PDF Conduit**. It talks to the Spring Boot REST
API (`pdf-utils-web`) at `/api`. This is a **standalone project**, not a Maven
module — it has its own npm build and nginx Docker image.

## Prerequisites

- Node.js **18.19.1+** and npm 9+
- The backend running at `http://localhost:8080` for live API calls (dev proxy
  forwards `/api` there). See `pdf-utils-web`.

## Development

```bash
npm install          # first time
npm start            # ng serve → http://localhost:4200 (proxies /api → :8080)
```

`proxy.conf.json` maps `/api` to `http://localhost:8080`; it is wired into
`ng serve` via `angular.json` (`serve.options.proxyConfig`). Start the backend
separately, then the health indicator in the header turns green.

## Build

```bash
npm run build        # production bundle → dist/pdf-utils-frontend/browser
```

## Test

```bash
npm test             # Karma/Jasmine unit tests
```

## Docker

Multi-stage build (`node:18` build → `nginx:alpine` serving the bundle). nginx
serves the SPA with history-API fallback and reverse-proxies `/api/` to the
`backend` service.

```bash
docker build -t pdf-conduit-frontend .
docker run --rm -p 8081:80 pdf-conduit-frontend   # needs a reachable backend for /api
```

In `docker-compose`, run this alongside `pdf-utils-web` as `backend`.

## Structure

```
src/app/
  core/         ApiService, models, ThemeService, download util, operations catalog
  layout/       header (health + theme toggle), sidebar (grouped nav)
  shared/       file-drop-zone, result-panel, spinner, operation-placeholder
  pages/        one lazy-loaded page per operation (scaffold placeholders)
  app.routes.ts routes for every operation + wizard + pipeline
src/environments/  environment.ts (prod) / environment.development.ts (dev)
proxy.conf.json    dev-server /api proxy
Dockerfile, nginx.conf, .dockerignore   nginx deployment
```

### ApiService

`core/ApiService` POSTs multipart `FormData` to each endpoint and returns a
`RunResult` (`{ blob, filename, contentType, compression?, batchFailures? }`),
parsing the download filename from `Content-Disposition` and compress stats from
`X-Original-Bytes` / `X-Result-Bytes` / `X-Target-Reached`. Errors become a typed
`ApiError { code, message, status }`. It also exposes `getHealth()`,
`getOperations()`, `readMetadata()` and a generic `runOperation(endpoint, formData)`.

The base URL comes from `environment.apiBase` (empty in dev and prod — the
dev-server proxy / nginx handle same-origin `/api`).

> Operation pages are currently scaffold placeholders. A later agent implements
> each form (drop-zone + options) and the wizard / pipeline / redaction UIs.
