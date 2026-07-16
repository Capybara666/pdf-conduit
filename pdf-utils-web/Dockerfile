# syntax=docker/dockerfile:1
#
# Multi-stage build for the PDF Conduit web module (pdf-utils-web).
#
# Stage 1 builds the whole Maven reactor (root pom + the three modules) far
# enough to produce the Spring Boot fat jar; stage 2 is a slim JRE image with a
# headless LibreOffice so office/text documents can be converted server-side.
#
# The build context is the REPOSITORY ROOT (see docker-compose.yml: context: .),
# because the reactor build needs the root pom and pdf-utils-core alongside this
# module. A repo-root-aware `.dockerignore` (pdf-utils-web/.dockerignore) keeps
# that context small by excluding target/, .git, docs/, IDE files, etc.
#
# NOTE: images are NOT built here. Build with `docker compose up --build`.

# ---------------------------------------------------------------------------
# Stage 1 — build the fat jar with Maven + JDK 21
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy the entire reactor (root pom + all three modules). The .dockerignore
# strips build output and other noise so this stays lean.
COPY . .

# Build only the web module and what it depends on (-am also builds
# pdf-utils-core). pdf-utils-app is left out of this reactor slice, so no
# JavaFX is required at build time. spring-boot:repackage (bound in the module
# pom) produces the runnable fat jar.
RUN mvn -q -pl pdf-utils-web -am -DskipTests package

# ---------------------------------------------------------------------------
# Stage 2 — slim runtime: JRE 21 + headless LibreOffice
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime

# LibreOffice (headless) lets DocumentConverter turn .docx/.odt/.xlsx/… into
# PDF. fonts-dejavu + fontconfig give conversions a sane default font; curl is
# used by the container HEALTHCHECK below. --no-install-recommends keeps the
# image small.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        libreoffice-core \
        libreoffice-writer \
        libreoffice-calc \
        libreoffice-impress \
        fonts-dejavu \
        fontconfig \
        curl \
    && rm -rf /var/lib/apt/lists/*

# Run as a non-root user.
RUN useradd --system --create-home --uid 10001 appuser

WORKDIR /app

# The Spring Boot fat jar (finalName pdf-utils-web-1.0.0, from the module pom).
COPY --from=build /build/pdf-utils-web/target/pdf-utils-web-1.0.0.jar /app/app.jar

# Writable base work dir for temp uploads/results, owned by the app user.
# Matches PDFCONDUIT_WEB_WORK_DIR below.
RUN mkdir -p /work && chown -R appuser:appuser /work /app
ENV PDFCONDUIT_WEB_WORK_DIR=/work

USER appuser

# Default HTTP port (overridable via SERVER_PORT at runtime).
EXPOSE 8080

# Liveness: the app exposes GET /api/health returning {status:"UP"}.
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD curl -fsS "http://localhost:${SERVER_PORT:-8080}/api/health" || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
