#!/usr/bin/env bash
#
# Dev launcher for the PDF Conduit web FRONTEND (pdf-utils-frontend) — the
# Angular single-page app.
#   - Runs the Angular dev server (`ng serve` via `npm start`) on
#     http://localhost:4200.
#   - Proxies /api → http://localhost:8080 (see pdf-utils-frontend/proxy.conf.json),
#     so start the backend first with scripts/run-web.sh in another terminal.
#
# This is the standalone npm build — it is NOT part of the Maven reactor, so the
# desktop/core Java build never needs Node. For a self-contained production run
# of both tiers (nginx + backend, LibreOffice bundled), use Docker instead:
# `docker compose up --build`.
#
# Requirements: Node 18+, npm. First run installs dependencies (`npm install`).
set -euo pipefail

# Move to the frontend module (sibling of this script's parent dir).
cd "$(dirname "$0")/../pdf-utils-frontend"

# Install dependencies on first run (no node_modules yet).
if [ ! -d node_modules ]; then
  echo "==> Installing frontend dependencies (npm install)"
  npm install
fi

echo "==> Starting PDF Conduit frontend (ng serve)"
echo "    Once started, open: http://localhost:4200"
echo "    /api is proxied to the backend on :8080 (run scripts/run-web.sh)."
echo "    Press Ctrl-C to stop."

exec npm start
