<p align="center"><img src="assets/logo.svg" alt="PDF Conduit" width="112"></p>

# PDF Conduit

PDF Conduit is a privacy-first, all-in-one PDF toolkit. Every operation runs
server-side **in memory** — uploaded files are processed per request and never
stored. It ships as a free web app at **[pdf-conduit.com](https://pdf-conduit.com)**
(Angular SPA + stateless Spring Boot REST API), a JavaFX **desktop app** with a
full **CLI**, and a self-hostable **REST API**, all built on the same
[Apache PDFBox](https://pdfbox.apache.org/)-based core.

> Desktop users: the [User Guide](USER_GUIDE.md) walks through every feature of
> the desktop app and the CLI.

## Features

| Operation | What it does | Web | Desktop |
| --- | --- | :---: | :---: |
| **Merge** | Combine PDFs, images or office docs into one document | ✔ | ✔ |
| **Extract** | Pull selected pages out — into one PDF or one file per page | ✔ | ✔ |
| **Rotate** | Rotate selected pages 90°, 180° or 270° | ✔ | ✔ |
| **Arrange** | Reorder, reverse, duplicate or drop pages | ✔ | ✔ |
| **Pages per sheet** | N-up imposition (2/4/6/8/9-up) and booklet folding | ✔ | — |
| **Compress** | Shrink toward a target size (web adds max-DPI and grayscale options) | ✔ | ✔ |
| **To PDF** | Convert images, office docs, Markdown and HTML (with formatting) to PDF | ✔ | ✔ |
| **To Images** | Render PDF pages to PNG or JPG | ✔ | — |
| **To Text** | Extract text content as `.txt` or `.docx` | ✔ | — |
| **OCR** | Make a scanned PDF searchable | ✔ | — |
| **Protect** | Password-encrypt (AES-128; web adds AES-256) | ✔ | ✔ |
| **Unlock** | Remove a known password | ✔ | ✔ |
| **Redact** | Permanently black out regions (pages are rasterized, so the data is gone) | ✔ | — |
| **Fill & Sign** | Fill AcroForm fields, place a signature, flatten | ✔ | — |
| **Metadata** | View, edit or strip document info | ✔ | ✔ |
| **Watermark** | Stamp text or an image over every page (web adds tiling and positions) | ✔ | ✔ |
| **Crop** | Trim margins off every page | ✔ | — |
| **Page Marks** | Page numbers, headers/footers, Bates numbering | ✔ | — |
| **GDPR / PII scan** | Detect personal data, then auto-redact the findings (single or batch) | ✔ | — |
| **Wizard** | Guided select → arrange → compress → export flow | ✔ | ✔ |
| **Pipeline** | Visual node editor: wire operations into a graph, save/load as JSON | ✔ | ✔ |

Everywhere, non-PDF inputs (images, `.docx`, `.odt`, `.xlsx`, `.pptx`, `.rtf`,
`.txt`, …) are converted to PDF automatically — images inline, office documents
via headless LibreOffice — so there is no separate conversion step. The desktop
app additionally offers batch mode (per-file operations over a whole folder) and
runs saved pipelines from the CLI (`pdf-conduit pipeline my.json`).

The GDPR/PII scanner works offline in server memory: it flags emails, phone
numbers, IP addresses, IBANs, payment card numbers (checksum-validated),
national IDs and special-category keywords, reports a risk level with masked
samples, and hands the exact finding positions to the Redact tool for a
one-click cleanup.

## Privacy

- The backend is **stateless**: files are processed in memory per request and
  the result is streamed straight back. File contents are never written to disk
  or retained after the response.
- **Single exception:** office/text-document conversion requires LibreOffice,
  which runs in an isolated per-request temp directory that is deleted
  immediately afterwards.
- The live service's policy is published at
  [pdf-conduit.com/privacy](https://pdf-conduit.com/privacy).

## Architecture

Three Maven modules (Java 21) plus a standalone Angular frontend. The core
library exposes two behaviour-compatible APIs over the same algorithms: a
`Path`-in/`Path`-out API used by the desktop app, and an in-memory `byte[]` API
used by the web backend so uploads never touch disk.

- **`pdf-utils-core`** — pure PDFBox library: all operations, models, page-range
  parsing, document conversion, the GDPR/PII scanner, and the JavaFX-free
  pipeline model + executor. No UI dependencies; headlessly testable.
- **`pdf-utils-desktop`** — JavaFX GUI + picocli CLI in one entry point (no
  arguments opens the GUI, arguments run the CLI).
- **`pdf-utils-web`** — stateless, API-only Spring Boot REST backend under
  `/api`, with per-IP rate limiting, daily quotas, concurrency/memory guards and
  processing timeouts for safe public hosting.
- **`pdf-utils-frontend`** — Angular 18 SPA (own npm build, not part of the
  Maven reactor); nginx serves the SPA and proxies `/api` to the backend.

## Quick start

### Web (Docker)

```bash
docker compose up --build     # then open http://localhost:4200
```

LibreOffice is bundled in the backend image, so office conversion works out of
the box. For a production deployment with automatic HTTPS (Caddy + Let's
Encrypt), hardening and tuning, follow the step-by-step guide in
[`deploy/README.md`](deploy/README.md). Configuration knobs are documented in
`.env.example`.

### Desktop

Requires JDK 21+ and Maven 3.9+ (LibreOffice optional, only for office/text
conversion):

```bash
mvn package                         # build everything
cd pdf-utils-desktop && mvn javafx:run
```

Native installers (bundled runtime, no JDK needed by end users) are produced by
`scripts/build-linux.sh` (app-image + `.deb`) and `scripts/build-windows.ps1`
(app-image + `.exe`); see `scripts/README.md`.

### CLI

The desktop entry point dispatches to the CLI when given arguments; the native
package installs a `pdf-conduit` launcher:

```bash
pdf-conduit merge a.pdf b.pdf images/*.png -o combined.pdf
pdf-conduit split report.pdf --pages 1-3,5,end-2 -o pages.pdf
pdf-conduit compress scan.pdf --target-size 5MB -o smaller.pdf
pdf-conduit rotate doc.pdf --pages 1,3 --angle 90 -o rotated.pdf
pdf-conduit arrange doc.pdf --order 3,1,2 -o reordered.pdf
pdf-conduit to-pdf *.jpg --page-size A4 -o album.pdf
pdf-conduit protect secret.pdf --password s3cret -o locked.pdf
pdf-conduit unlock locked.pdf --password s3cret -o open.pdf
pdf-conduit metadata report.pdf --title "Q3 Report" --author Me -o tagged.pdf
pdf-conduit watermark report.pdf --text DRAFT --opacity 0.3 -o stamped.pdf
pdf-conduit pipeline my-pipeline.json    # run a pipeline saved from the GUI
```

Page ranges accept `1`, `2-5`, `1,3,5-8` and `end-2`; sizes accept `500KB`,
`5MB`, `1.5MB`. Exit codes: 0 success, 1 bad input, 2 operation failed.

## Languages and themes

- **Web:** 14 UI languages (en, pl, es, zh, de, fr, it, pt, nl, uk, ru, tr, ja,
  ko), switchable live from the header.
- **Desktop:** 4 languages (English, Polish, Spanish, Chinese); switching never
  discards loaded files or in-progress work.
- **Both:** six color themes (Daylight, Graphite, Nord, Dracula, Solarized,
  Sunset), plus a System option on desktop.

## Build and test

```bash
mvn package                                        # build all Maven modules
mvn test                                           # run all tests
mvn test -pl pdf-utils-core -Dtest=PdfMergerTest   # a single test class
cd pdf-utils-frontend && npm start                 # Angular dev server (:4200)
```

## License

PDF Conduit is licensed under the terms in [`LICENSE`](LICENSE) (End User
License Agreement). Bundled third-party open-source components and their
licenses are listed in [`THIRD-PARTY-LICENSES.md`](THIRD-PARTY-LICENSES.md),
with full license texts in [`licenses/`](licenses/) and attribution notices in
[`NOTICE`](NOTICE).
