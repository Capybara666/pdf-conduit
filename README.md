<p align="center"><img src="assets/logo.svg" alt="PDF Conduit" width="112"></p>

# PDF Conduit

A small desktop toolkit for everyday PDF tasks — **merge, extract pages,
compress to a target size, rotate, arrange (reorder) pages, and convert images &
documents to PDF** — with both a JavaFX GUI and a command-line interface, built on
[Apache PDFBox](https://pdfbox.apache.org/).

> **New here?** The [User Guide](USER_GUIDE.md) walks through every feature of the
> app and the CLI.

## Features

- **Core operations:** merge PDFs/images into one document, extract pages — into
  one PDF or split into a separate file per page — compress to a target file size
  (iterative image downsampling), rotate pages, arrange (reorder, duplicate or
  drop) pages, convert files to PDF (one PDF per input), password-protect or
  unlock PDFs (AES-128), view/edit/strip metadata, and stamp text or image
  watermarks.
- **Any supported input, anywhere:** every operation accepts images and office
  documents (`.docx`, `.odt`, `.rtf`, `.txt`, `.xlsx`, `.pptx`, …) in addition to
  PDFs — non-PDF files are converted to PDF automatically (images inline; office
  documents via a headless LibreOffice). Pipelines convert source files on the
  fly, so no separate "Images → PDF" step is needed.
- **Languages:** English, Polish, Spanish and Chinese, switchable live from the
  Language menu (switching never discards your loaded files or in-progress work).
- **GUI (JavaFX):**
  - A panel per operation with drag-and-drop, a file list, and live progress.
  - **Batch mode** — per-file operations (Extract/Compress/Rotate/To PDF)
    process every selected file into an output folder.
  - A guided **Wizard** for the merge → arrange → compress → export flow.
  - **Pipelines** — a visual node editor: drag operation/source blocks onto a
    canvas, wire outputs into inputs, and run the whole graph. Edges carry
    bundles of documents (map operations — including To PDF — apply per file;
    only Merge collapses a bundle into one). Pipelines can be **saved and loaded**
    as `.json` and re-run from the CLI (`pdf-conduit pipeline my.json`).
  - Outputs default to a `pdf-conduit` folder in your Documents directory.
  - Six color themes (Daylight, Graphite, Nord, Dracula, Solarized, Sunset) plus
    a System option, with subtle UI animations.
- **CLI** mirroring every core operation, with exit codes (0 success, 1 bad
  input, 2 operation failed).

## Requirements

- **JDK 21+** (the release scripts also use its bundled `jpackage`).
- **Maven 3.9+**.
- **LibreOffice** *(optional)* — only needed to convert office/text documents to
  PDF. Without it, PDFs and images still work; document conversion reports a clear
  "LibreOffice is not installed" message.

## Build & test

```bash
mvn package        # build everything
mvn test           # run all tests
```

## Run the GUI

```bash
cd pdf-utils-desktop && mvn javafx:run
```

Launching with no arguments opens the GUI; launching with arguments runs the
CLI (see below). The window opens centered on your primary monitor.

In IntelliJ IDEA, a shared **PdfUtils GUI** run configuration is checked into
`.idea/runConfigurations/` and appears automatically after importing the
project. The Maven `javafx:run` command above works regardless of IDE.

## CLI

The application entry point dispatches to the CLI when given arguments. Once you
build a native package (see *Releases*), the launcher is `pdf-conduit`:

```bash
pdf-conduit merge a.pdf b.pdf images/*.png -o combined.pdf
pdf-conduit split report.pdf --pages 1-3,5,end-2 -o pages.pdf
pdf-conduit split report.pdf --separate -o pages/   # one PDF per page into a folder
pdf-conduit compress scan.pdf --target-size 5MB -o smaller.pdf
pdf-conduit rotate doc.pdf --pages 1,3 --angle 90 -o rotated.pdf
pdf-conduit arrange doc.pdf --order 3,1,2 -o reordered.pdf   # reorder pages; 5-1 reverses, repeat to duplicate
pdf-conduit to-pdf *.jpg --page-size A4 -o album.pdf   # combines images into one PDF (alias of images-to-pdf)
pdf-conduit protect secret.pdf --password s3cret -o locked.pdf      # AES-128 password protection
pdf-conduit unlock locked.pdf --password s3cret -o open.pdf         # remove the password
pdf-conduit metadata report.pdf --show                             # print title/author/subject/keywords
pdf-conduit metadata report.pdf --title "Q3 Report" --author Me -o tagged.pdf
pdf-conduit metadata report.pdf --strip -o clean.pdf               # remove all metadata
pdf-conduit watermark report.pdf --text DRAFT --opacity 0.3 --scale 0.9 -o stamped.pdf
pdf-conduit watermark report.pdf --image logo.png --rotation 0 -o branded.pdf  # --scale 0.05-2 sizes it
pdf-conduit pipeline my-pipeline.json   # run a pipeline saved from the GUI
```

- **Page ranges:** `1`, `2-5`, `1,3,5-8`, `end-2` (relative to the last page).
- **Sizes:** `500KB`, `5MB`, `1.5MB`.

## Web version

The web version is a **two-tier app** covering every desktop operation:

- **`pdf-utils-frontend`** — an **Angular 18** single-page app (its own npm
  build, *not* part of the Maven reactor). All operations plus the Wizard, the
  visual Pipeline builder, and in-browser Redaction (pdf.js box-drawing).
- **`pdf-utils-web`** — a **stateless, in-memory Spring Boot REST API** (no
  browser UI of its own). It reuses `pdf-utils-core`, so behaviour matches the
  desktop app, and processes uploads entirely in memory — nothing is written to
  disk. The one exception is **office/text-document conversion** (`.docx`,
  `.xlsx`, …), which LibreOffice performs in an isolated, immediately-deleted
  per-request temp dir.

**Run in dev** (two terminals):

```bash
scripts/run-web.sh        # backend REST API  → http://localhost:8080/api
scripts/run-frontend.sh   # Angular dev server → http://localhost:4200
```

The frontend dev server proxies `/api` to the backend on `:8080`, so open
**http://localhost:4200**. (Equivalents: `mvn -pl pdf-utils-web -am
spring-boot:run` and `cd pdf-utils-frontend && npm start`.)

**Run with Docker** (production; two containers — nginx-served SPA + backend,
with a headless LibreOffice bundled so office-doc conversion works out of the
box; images are built from source, not pre-published):

```bash
docker compose up --build      # then open http://localhost:4200
```

nginx serves the SPA and reverse-proxies `/api` to the `backend` service, so the
browser talks to a single origin (no CORS needed in this topology). Only the
frontend publishes a host port; the backend is internal.

**Configuration** (environment variables; see `.env.example`, copy to `.env`):

| Variable | Default | Purpose |
|----------|---------|---------|
| `FRONTEND_PORT` | `4200` | Host port the frontend (nginx) is published on |
| `SERVER_PORT` | `8080` | Backend HTTP port (internal in compose) |
| `PDFCONDUIT_WEB_OFFICE_ENABLED` | `true` | Enable office-doc conversion (the sole disk touch); `false` rejects office uploads with a 415 |
| `PDFCONDUIT_WEB_CORS_ALLOWED_ORIGINS` | `http://localhost:4200` | Origins allowed to call `/api` (only relevant for the dev split-origin setup) |
| `PDFCONDUIT_WEB_SOFFICE_PATH` | auto-detect | Explicit path to `soffice` |
| `PDFCONDUIT_WEB_MAX_FILE_SIZE` | `100MB` | Max size per uploaded file |
| `PDFCONDUIT_WEB_MAX_REQUEST_SIZE` | `200MB` | Max total request size |
| `PDFCONDUIT_WEB_MAX_FILES` | `50` | Max files per request |

As with the desktop app, converting **office/text documents** to PDF needs
LibreOffice (the `soffice` command) on the host — it is bundled in the Docker
image, so `docker compose up` needs no extra setup. Pure PDF and image flows
are fully in-memory and work without it.

## Releases (native packages)

`jpackage`-based scripts produce a self-contained app (bundled Java runtime, no
JDK required by the user). Run each **on its target OS** — jpackage does not
cross-compile.

```bash
scripts/build-linux.sh        # Ubuntu/Linux  → portable app-image + .deb
scripts/build-windows.ps1     # Windows       → portable app-image + .exe
```

Output lands in `dist/`. See `scripts/README.md` for prerequisites.

## Project layout

```
pdf-utils-core/     pure library — PDFBox operations, models, utils (no JavaFX/CLI).
                    Path API (desktop) + in-memory byte[] API (web).
pdf-utils-desktop/  entry point: CLI (picocli) + GUI (JavaFX), depends on core
pdf-utils-web/      stateless, in-memory Spring Boot REST API (no UI of its own)
pdf-utils-frontend/ Angular 18 SPA (standalone npm build, not a Maven module)
scripts/            jpackage release builders + web/frontend dev launchers
```

- `pdf-utils-core` is dependency-light and headlessly testable.
- The pipeline **model + executor** (`pdf-utils-core/.../pipeline`) are
  JavaFX-free and unit-tested, so the CLI runs pipelines without the GUI; the
  canvas (`pdf-utils-desktop/.../gui/pipeline`) is the JavaFX layer on top.

## Testing

```bash
mvn test -pl pdf-utils-core                       # core operations
mvn test -pl pdf-utils-desktop                     # CLI + pipeline + stylesheet tests
mvn test -pl pdf-utils-core -Dtest=PdfMergerTest   # a single class
```

GUI interactions (canvas dragging, animations) are verified manually with
`mvn javafx:run`; everything else is covered by automated tests.
