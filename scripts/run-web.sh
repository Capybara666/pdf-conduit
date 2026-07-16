#!/usr/bin/env bash
#
# Dev launcher for the PDF Conduit web app (pdf-utils-web).
#   - Builds pdf-utils-core (its dependency) and starts the Spring Boot server
#     via the spring-boot-maven-plugin's run goal.
#   - Serves on http://localhost:8080 (override with SERVER_PORT).
#
# This runs from source with your host JDK/Maven — no Docker involved. Office
# document conversion needs a local LibreOffice (`soffice`); pure PDF/image
# flows do not. For a self-contained, LibreOffice-bundled run, use Docker
# instead: `docker compose up --build`.
#
# Requirements: JDK 21+, Maven 3.9+.
set -euo pipefail

# Move to the repository root (parent of this script's dir).
cd "$(dirname "$0")/.."

URL="http://localhost:${SERVER_PORT:-8080}"

echo "==> Starting PDF Conduit web (building pdf-utils-core + pdf-utils-web)"
echo "    Once started, open: ${URL}"
echo "    Press Ctrl-C to stop."

# -am also builds the pdf-utils-core dependency; spring-boot:run launches the app.
exec mvn -q -pl pdf-utils-web -am spring-boot:run
