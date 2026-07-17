#!/usr/bin/env bash
#
# Dev launcher for the PDF Conduit web BACKEND (pdf-utils-web) — the stateless,
# in-memory Spring Boot REST API.
#   - Builds pdf-utils-core (its dependency) and starts the API via the
#     spring-boot-maven-plugin's run goal.
#   - Serves the REST API on http://localhost:8080/api (override with SERVER_PORT).
#
# There is no bundled UI here — run the Angular frontend separately with
# scripts/run-frontend.sh (ng serve on :4200, proxying /api to this backend).
#
# This runs from source with your host JDK/Maven — no Docker involved. Office
# document conversion needs a local LibreOffice (`soffice`); pure PDF/image
# flows do not. For a self-contained, LibreOffice-bundled run of both tiers,
# use Docker instead: `docker compose up --build`.
#
# Requirements: JDK 21+, Maven 3.9+.
set -euo pipefail

# Move to the repository root (parent of this script's dir).
cd "$(dirname "$0")/.."

URL="http://localhost:${SERVER_PORT:-8080}/api"

echo "==> Starting PDF Conduit web backend (building pdf-utils-core + pdf-utils-web)"
echo "    REST API base: ${URL}  (try: ${URL}/health)"
echo "    Frontend dev server: run scripts/run-frontend.sh in another terminal."
echo "    Press Ctrl-C to stop."

# -am also builds the pdf-utils-core dependency; spring-boot:run launches the app.
exec mvn -q -pl pdf-utils-web -am spring-boot:run
