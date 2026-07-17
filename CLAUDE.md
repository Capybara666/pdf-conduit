# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build everything
mvn package

# Run all tests (run the full reactor — a single -pl run can use a stale core jar)
mvn test

# Run tests for a single module
mvn test -pl pdf-utils-core
mvn test -pl pdf-utils-desktop

# Run a single test class
mvn test -pl pdf-utils-core -Dtest=PdfMergerTest
mvn test -pl pdf-utils-desktop -Dtest=CliIntegrationTest

# Launch the GUI (JavaFX)
cd pdf-utils-desktop && mvn javafx:run

# Build a native, self-contained package (bundled JRE, via jpackage).
# Run each on its target OS — jpackage does not cross-compile. Output → dist/.
scripts/build-linux.sh        # Linux   → portable app-image + .deb
scripts/build-windows.ps1     # Windows → portable app-image + .exe
```

The release scripts activate the Maven `dist` profile (in `pdf-utils-desktop/pom.xml`),
which copies the runtime dependencies — including the platform-specific JavaFX
native jars — into `target/dist-lib` for jpackage to bundle.

## Architecture

**Three** Maven modules, Java 21 (`maven.compiler.source`/`target` = 21), plus a
standalone Angular front end that is **not** in the Maven reactor:
- **`pdf-utils-core`** — shared PDFBox library (below). Exposes two parallel
  APIs: the original **`Path`-in/`Path`-out** API (used by desktop) and a **new
  in-memory `byte[]` API** (used by the web backend so it never touches disk).
- **`pdf-utils-desktop`** — JavaFX GUI + picocli CLI (formerly `pdf-utils-app`).
- **`pdf-utils-web`** — a **stateless, in-memory, API-only** Spring Boot REST
  backend (no bundled UI; see "Web stack" below).
- **`pdf-utils-frontend`** — an **Angular 18** SPA with its own npm build (own
  `package.json`/`nginx` Dockerfile); the Java reactor never needs Node.

The product name is **PDF Conduit**; the Maven artifact ids are `pdf-utils-*`.

**`pdf-utils-core`** — pure library, no JavaFX, no picocli. Depends on Apache
PDFBox 3.x, the TwelveMonkeys `imageio-webp` reader (a pure-Java WebP decoder
auto-registered with ImageIO, so `.webp` inputs decode — stock JDK ImageIO has no
WebP reader; TIFF/BMP/GIF/PNG/JPEG it already handles), and Gson (to persist
pipelines).
- **In-memory `byte[]` API** (parallel to the `Path` API; used by the web
  backend): `service/MemoryOperations` (bytes analog of `OperationRunner` —
  single/batch/multi-output over `List<byte[]>`), the operations' `executeBytes`
  variants, `convert/DocumentConverter.ensurePdfBytes` (in-memory input routing),
  and `pipeline/PipelineExecutor.runInMemory` (Documents carried as `byte[]`
  between nodes, no temp files). The `Path` API stays 100% behaviour-compatible —
  both share the same `PDDocument`-level algorithms.
- `operations/` — stateless utility classes (`final`, private constructor, static
  `execute(Options)`): `PdfMerger`, `PdfSplitter`, `PdfCompressor`, `PdfRotator`,
  `PdfArranger`, `ImageToPdfConverter`, `PdfProtector` (AES-128 password),
  `PdfUnlocker` (remove password), `PdfMetadataEditor` (read/edit/strip document
  info), `PdfWatermarker` (text or image watermark). Each takes an options record
  and returns a typed result record. **Every operation loads its input through
  `util/PdfLoader`** so protected / damaged files yield clear messages, not raw PDFBox.
- `service/` — transport-agnostic operation layer shared by every surface (CLI,
  GUI, pipeline, and a future web frontend). `OperationType` is the **single source
  of truth** for each operation's stable `id`, output-name `suffix`, `Cardinality`
  (MAP/REDUCE) and multi-output flag; `NodeKind` and the GUI `SidebarItem` map onto
  it. `OperationRunner` is the reusable plumbing — convert a raw input via
  `DocumentConverter`, run the work, name the output from the suffix, clean up temps —
  with `Execution` (the per-input work) and `ProgressSink` (transport-agnostic
  progress). Add a new operation's identity/suffix here, not scattered per surface.
- `convert/` — `DocumentConverter`: turns any supported input into a PDF so every
  operation can accept more than PDFs. PDFs pass through, images render inline,
  and office/text documents (`.docx`, `.odt`, `.rtf`, `.xlsx`, `.pptx`, `.txt`, …)
  are converted by a headless **LibreOffice** (`soffice`) if one is installed
  (located lazily; per-call user profile so conversions are concurrency-safe).
- `model/` — records for options and results (`MergeOptions`/`MergeResult`,
  `SplitOptions`/`SplitResult`, `CompressOptions`/`CompressResult`,
  `RotateOptions`/`RotateResult`, `ArrangeOptions`/`ArrangeResult`, `PdfResult`,
  `ImageToPdfOptions`), the enums `PageSize`, `SplitMode`, and the page-selection
  types `PageRange`. `PageSource` is a sealed interface with two permitted records:
  `PdfPageSource(Path, PageRange)` and `ImageSource(Path, PageSize)`.
- `util/` — `PageRangeParser` (syntax: `1`, `2-5`, `1,3,5-8`, `end-2`),
  `PageOrderParser` (arrange syntax, e.g. `3,1,2`, `5-1` to reverse, repeats to
  duplicate), `PageRangeFormatter`, `FileTypeDetector` (magic-byte sniffing),
  `SizeEstimator`, `OutputPaths`, `PdfLoader` (loads a PDF, mapping PDFBox's
  password-protected / wrong-password / damaged failures to clear messages).
- `exception/` — checked: `PdfOperationException`, `InvalidPageRangeException`.
- `pipeline/` — **JavaFX-free** visual-pipeline core, fully unit-tested.
  `PipelineModel` holds `PipelineNode`s and `Connection`s; `PipelineGraph` does
  the topological order / cycle detection / output-type inference; `PipelineValidator`
  checks a graph before it runs; `PipelineExecutor` runs it, threading bundles of
  `Document`s between nodes via temp files; `PipelineStore` saves/loads a model as
  JSON. `NodeKind` classifies nodes as source, *map* (one output document per input
  — Extract/Compress/Rotate/Arrange/To PDF/Protect/Unlock/Metadata/Watermark) or
  *reduce* (collapse a whole bundle into one — Merge); its suffix/cardinality are
  delegated to `service/OperationType` (the JSON format still serialises by `name()`).
  Lives in core so the CLI (and a future alternate frontend) can run pipelines
  without the GUI.

**`pdf-utils-desktop`** — entry point for both GUI and CLI, depends on core.
- `Main.java` — dispatches: args present → picocli `RootCommand`; no args →
  `GuiLauncher` (JavaFX).
- `cli/` — picocli subcommands mirroring each core operation (`merge`, `split`,
  `compress`, `rotate`, `arrange`, `to-pdf`/`images-to-pdf`, `protect`, `unlock`,
  `metadata`, `watermark`, `pipeline` — the last runs a saved `.json` pipeline);
  `SizeConverter` handles `500KB`/`5MB`/`1.5MB` syntax. `CliSources` routes each
  input by type (PDF / image / office) through `DocumentConverter`, so `merge` and
  `to-pdf` accept the same inputs as the GUI (office docs need LibreOffice). Exit
  codes: 0 success, 1 bad input, 2 operation failed.
- `i18n/` — `I18n`: tiny localisation helper over `i18n/messages*.properties`
  (UTF-8). English is the base bundle; `pl`, `es`, `zh` are translations. The
  chosen language is persisted (`java.util.prefs`) and listeners re-translate the
  UI **in place** (via `I18n.bindText`) so switching language never discards the
  user's loaded files / pipeline / wizard progress.
- `gui/` — JavaFX. `MainWindow` owns a `SidebarController` (`SidebarItem` enum:
  Merge, Extract, Compress, Rotate, Arrange, To PDF, Protect, Unlock, Metadata,
  Watermark, Pipeline, Wizard) and a
  `StackPane` content area that swaps between panels, the pipeline view and the
  wizard. Adding a sidebar operation means: a `SidebarItem` enum value, an icon
  in `Icons.of(SidebarItem)`, a `MainWindow.createPanel` case, a panel, and
  i18n keys in **all four** `messages*.properties` (guarded by `MessagesParityTest`). The window re-centres on the monitor under the cursor each launch and
  remembers its size.
  - `panels/` — `BasePanel` (abstract `VBox`) provides the shared DropZone +
    FileListView + output path + ProgressPanel layout; each operation subclasses
    it. Per-file operations run in **batch mode** over a folder of inputs.
  - `wizard/` — `WizardController` owns `WizardModel` (shared state across steps)
    and drives five `WizardStep`s (select → arrange → page settings → compression
    → export). The step indicator is built programmatically (no FXML).
  - `pipeline/` — the JavaFX layer over `core.pipeline`: `PipelineView`,
    `PipelineCanvas`, `NodeView`, `ConnectionView`, `NodeInspector` — the visual
    node editor, with Save/Load (`PipelineStore`). The palette is a wrapping
    `FlowPane`, so new node kinds add rows rather than overflow.
  - `component/` — reusable controls: `DropZone`, `FileListView`, `ProgressPanel`,
    `PageReorderGrid`, `PageSelectGrid`/`PageSelectDialog`, `DragReorder`.
  - `util/` — `DefaultLocations`, `FileOpener`, `PdfThumbnails`, `Sfx` (sound effects).
  - `ThemeManager` — reads/writes `java.util.prefs.Preferences`; detects OS theme
    via `gsettings` (Linux) or registry (Windows); applies one of the bundled CSS
    themes to the scene.

### Web stack (`pdf-utils-web` + `pdf-utils-frontend`)

The web version is two deployables, wired by `docker-compose.yml` (`backend` +
`frontend` services; `.env.example` for config):
- **`pdf-utils-web`** — an **API-only, stateless, in-memory** Spring Boot backend
  built on core's `byte[ary]` API (`MemoryOperations` / `runInMemory`). Every
  endpoint is under **`/api`** (`web/` controllers): the operations (`merge`,
  `extract`, `compress`, `rotate`, `arrange`, `to-pdf`, `protect`, `unlock`,
  `metadata`(+`/read`), `watermark`, `redact`, `to-images`, `to-text`), plus
  `pipeline/run` (multipart `pipeline` JSON + `files` → ZIP), `pipeline/validate`,
  `pipeline/kinds`, `render` (page → PNG), `health`, and `operations` (catalog).
  Batch MAP ops return a ZIP; single output streams the file; compress emits
  `X-Original-Bytes`/`X-Result-Bytes`/`X-Target-Reached`. Errors are
  `{code,error}` JSON via `GlobalExceptionHandler` (400 bad request / 413 too
  large / 415 office-disabled / 422 operation-failed / 500). CORS
  (`config/CorsConfig`) is configurable via `pdfconduit.web.cors.allowed-origins`.
  Config keys: `pdfconduit.web.{soffice-path,max-files-per-request,office.enabled,
  cors.allowed-origins}` — **no work-dir** (nothing hits disk).
- **`pdf-utils-frontend`** — the **Angular 18** SPA (all operations + Wizard +
  Pipeline builder + in-browser pdf.js Redaction). Standalone npm build, its own
  nginx Dockerfile serving the SPA and proxying `/api` → the `backend` service.
- **The one disk exception:** office/text conversion (`.docx`/`.xlsx`/…) needs
  LibreOffice, which reads/writes an isolated, immediately-deleted per-request
  temp dir; gated by `pdfconduit.web.office.enabled` (default true, else 415).
  Everything else — PDFs and images, including the pipeline — stays in memory.

## Key Conventions

- Core operations and stateless helpers are utility classes: `final` with a
  `private` constructor and only static methods (no instance fields). Match this
  when adding a new operation or util.
- Every operation accepts any supported input: the GUI/pipeline route non-PDF
  inputs through `DocumentConverter` first, so panels and pipeline nodes don't
  need a separate "convert to PDF" step. Office conversion needs LibreOffice; its
  absence yields a clear "LibreOffice is not installed" message rather than a crash.
  Prefer `service/OperationRunner` for the convert→run→name→cleanup plumbing
  (`runSingle`/`runBatch`) instead of re-implementing it per panel/command.
- An operation's identity lives once in `service/OperationType` (id, suffix,
  cardinality). Read the suffix from there (`OperationType.X.suffix()`) rather than
  hard-coding `"_compressed"` etc.; `BasePanel`, the CLI commands and `NodeKind`
  all derive theirs from the catalog. `MessagesParityTest`, `SidebarItemCatalogTest`
  and `NodeKindCatalogTest` guard that the surfaces stay in sync with the catalog.
- `PdfCompressor` first does a **lossless pass** — re-saving with object-stream
  compression (PDFBox's default, which also drops orphaned objects). If that meets
  the target, or the PDF has no images to downsample, it stops there (no quality
  loss, and image-free PDFs skip the ladder entirely). Otherwise it walks a ladder
  of `(scale, quality)` steps, gentlest first. The
  **full-resolution** rungs (scale `1.0`, decreasing JPEG quality) come *before*
  any downscaling, so a target reachable by re-encoding alone never sacrifices
  resolution; only once those are exhausted are images shrunk (`0.75 → 0.5 →
  0.33`). Each step re-encodes images as JPEG (so even lossless images at scale
  `1.0` get smaller), estimates size via `SizeEstimator`, and stops early when the
  target is met; if the target is unreachable it saves the smallest result (never
  larger than the input).
- GUI panels are instantiated lazily (`computeIfAbsent`) and cached in
  `MainWindow`; re-selecting a sidebar item returns the same panel instance.
- The pipeline **model + executor** live in `core.pipeline` and are deliberately
  JavaFX-free so they can be unit-tested headlessly (`PipelineExecutorTest`,
  `PipelineValidatorTest`, `PipelineStoreTest`) and run from the CLI; the canvas
  (`app.gui.pipeline`) is the JavaFX layer on top. Keep new pipeline logic in
  `core.pipeline`, not `app.gui.pipeline`. A new node kind touches: `service/
  OperationType` (id/suffix/cardinality), `NodeKind` (label + the catalog mapping),
  `PipelineNode` fields, the `PipelineExecutor` switch, exhaustive switches in
  `Icons.of(NodeKind)` and `NodeView.refreshSummary`, the `NodeInspector` and
  `PipelineView` palette, plus `kind.*` i18n keys.
- CSS theming: `base.css` holds shared styles; each theme file
  (`light`, `dark`, `nord`, `dracula`, `solarized`, `sunset`) imports `base.css`
  and overrides variables. The scene stylesheet list is replaced wholesale on
  theme change. A `StylesheetParseTest` guards every theme parses.
- i18n: add a key to **all** of `messages*.properties`; `MessagesParityTest`
  fails if a translation is missing a key. Use `I18n.t(key, args...)` for lookups
  and `I18n.bindText(setter, key, args...)` for labels that must re-translate live.
- `docs/` is in `.gitignore` — design specs, plans and session notes there are not
  committed. Do not add Co-Authored-By trailers to commits in this repo.
