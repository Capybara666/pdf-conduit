# CLAUDE.md

Guidance for Claude Code working in this repository.

## Commands

```bash
mvn package                                          # build everything
mvn test                                             # run ALL tests via the full reactor
                                                     #   (a single -pl run can use a stale core jar)
mvn test -pl pdf-utils-core                          # one module
mvn test -pl pdf-utils-core -Dtest=PdfMergerTest     # a single test class
cd pdf-utils-desktop && mvn javafx:run               # launch the GUI (JavaFX)
scripts/build-linux.sh                               # native package → dist/ (.deb + app-image)
scripts/build-windows.ps1                            # native package → dist/ (.exe + app-image)
```

Native packages use jpackage (bundled JRE) — run each on its target OS (no cross-compile). Release scripts activate the Maven `dist` profile (`pdf-utils-desktop/pom.xml`), copying runtime deps (incl. platform-specific JavaFX native jars) into `target/dist-lib` for jpackage.

## Architecture

Product name **PDF Conduit**; artifact ids `pdf-utils-*`; **Java 21**. **Three** Maven modules plus a standalone Angular frontend that is **not** in the reactor (reactor never needs Node):
- **`pdf-utils-core`** — pure PDFBox library (no JavaFX/picocli).
- **`pdf-utils-desktop`** — JavaFX GUI + picocli CLI (formerly `pdf-utils-app`).
- **`pdf-utils-web`** — stateless, API-only, in-memory Spring Boot REST backend.
- **`pdf-utils-frontend`** — Angular 18 SPA, own npm build + nginx Dockerfile.

### `pdf-utils-core`
PDFBox 3.x + TwelveMonkeys `imageio-webp` (pure-Java WebP decoder auto-registered with ImageIO; TIFF/BMP/GIF/PNG/JPEG already handled) + Gson (persist pipelines). **Two parallel APIs** sharing the same `PDDocument`-level algorithms, 100% behaviour-compatible:
- **`Path`-in/`Path`-out** (desktop).
- **In-memory `byte[]`** (web, never touches disk): `service/MemoryOperations` (bytes analog of `OperationRunner`, single/batch/multi-output over `List<byte[]>`), operations' `executeBytes` variants, `convert/DocumentConverter.ensurePdfBytes`, `pipeline/PipelineExecutor.runInMemory`.

Packages:
- `operations/` — stateless utility classes (`final`, private ctor, static `execute(Options)`; options record in → result record out): `PdfMerger`, `PdfSplitter`, `PdfCompressor`, `PdfRotator`, `PdfArranger`, `ImageToPdfConverter`, `PdfProtector` (AES-128), `PdfUnlocker`, `PdfMetadataEditor`, `PdfWatermarker`. **Every operation loads input via `util/PdfLoader`** (clear messages for protected/damaged files, not raw PDFBox).
- `service/` — transport-agnostic layer shared by CLI/GUI/pipeline/web. `OperationType` is the **single source of truth** for each operation's `id`, `suffix`, `Cardinality` (MAP/REDUCE) and multi-output flag; `NodeKind` + GUI `SidebarItem` map onto it. `OperationRunner` is the reusable convert→run→name→cleanup plumbing (`runSingle`/`runBatch`), with `Execution` + `ProgressSink`.
- `convert/` — `DocumentConverter`: any supported input → PDF (PDFs pass through, images render inline, office/text via headless **LibreOffice** `soffice` if installed — located lazily, per-call profile).
- `model/` — options/result records + enums `PageSize`, `SplitMode`; `PageRange`; sealed `PageSource` (`PdfPageSource(Path, PageRange)`, `ImageSource(Path, PageSize)`).
- `util/` — `PageRangeParser` (`1`, `2-5`, `1,3,5-8`, `end-2`), `PageOrderParser` (arrange: `3,1,2`, `5-1` reverse, repeats duplicate), `PageRangeFormatter`, `FileTypeDetector` (magic bytes), `SizeEstimator`, `OutputPaths`, `PdfLoader`. `exception/` — checked `PdfOperationException`, `InvalidPageRangeException`.
- `pipeline/` — **JavaFX-free** visual-pipeline core (unit-tested): `PipelineModel` (`PipelineNode`s + `Connection`s), `PipelineGraph` (topo order / cycle detection / output-type inference), `PipelineValidator`, `PipelineExecutor` (threads `Document` bundles via temp files), `PipelineStore` (JSON, by `name()`). `NodeKind` classifies source / *map* / *reduce* (Merge); suffix + cardinality delegated to `OperationType`. In core so CLI can run pipelines without the GUI.

### `pdf-utils-desktop`
Entry point for GUI + CLI; depends on core.
- `Main.java` — args present → picocli `RootCommand`; no args → `GuiLauncher` (JavaFX).
- `cli/` — picocli subcommands mirroring each operation (`merge`, `split`, `compress`, `rotate`, `arrange`, `to-pdf`/`images-to-pdf`, `protect`, `unlock`, `metadata`, `watermark`, `pipeline` — runs a saved `.json`). `SizeConverter` (`500KB`/`5MB`/`1.5MB`); `CliSources` routes input by type through `DocumentConverter`. Exit codes: 0 success, 1 bad input, 2 operation failed.
- `i18n/` — `I18n` over `i18n/messages*.properties` (UTF-8): English base + `pl`/`es`/`zh`. Language persisted (`java.util.prefs`); listeners re-translate in place via `I18n.bindText` so switching never discards loaded files / pipeline / wizard progress.
- `gui/` — JavaFX. `MainWindow` owns `SidebarController` (`SidebarItem`: Merge, Extract, Compress, Rotate, Arrange, To PDF, Protect, Unlock, Metadata, Watermark, Pipeline, Wizard) + a `StackPane` content area. Window re-centres on the monitor under the cursor, remembers its size.
  - `panels/` — `BasePanel` (abstract `VBox`): shared DropZone + FileListView + output + ProgressPanel; each operation subclasses it. Per-file ops run in **batch mode** over a folder.
  - `wizard/` — `WizardController` + `WizardModel` drive five `WizardStep`s (select → arrange → page settings → compression → export); step indicator built programmatically (no FXML).
  - `pipeline/` — JavaFX layer over `core.pipeline`: `PipelineView`, `PipelineCanvas`, `NodeView`, `ConnectionView`, `NodeInspector`; Save/Load via `PipelineStore`; palette is a wrapping `FlowPane`.
  - `component/` — `DropZone`, `FileListView`, `ProgressPanel`, `PageReorderGrid`, `PageSelectGrid`/`PageSelectDialog`, `DragReorder`. `util/` — `DefaultLocations`, `FileOpener`, `PdfThumbnails`, `Sfx`. `ThemeManager` — `java.util.prefs`; detects OS theme (`gsettings` Linux / registry Windows); applies a bundled CSS theme.

### Web stack (`pdf-utils-web` + `pdf-utils-frontend`)
Two deployables wired by `docker-compose.yml` (`backend` + `frontend` services; `.env.example`).
- **`pdf-utils-web`** — API-only stateless in-memory Spring Boot on core's `byte[]` API. Every endpoint under **`/api`** (`web/` controllers): operations (`merge`, `extract`, `compress`, `rotate`, `arrange`, `to-pdf`, `protect`, `unlock`, `metadata`(+`/read`), `watermark`, `redact`, `to-images`, `to-text`) + `pipeline/run` (multipart JSON + files → ZIP), `pipeline/validate`, `pipeline/kinds`, `render` (page → PNG), `health`, `operations` (catalog). Batch MAP → ZIP; single output streams; compress emits `X-Original-Bytes`/`X-Result-Bytes`/`X-Target-Reached`. Errors `{code,error}` JSON via `GlobalExceptionHandler` (400/413/415/422/500); CORS in `config/CorsConfig`. Config keys `pdfconduit.web.{soffice-path,max-files-per-request,office.enabled,cors.allowed-origins}` — **no work-dir** (nothing hits disk).
- **`pdf-utils-frontend`** — Angular 18 SPA (all operations + Wizard + Pipeline builder + in-browser pdf.js Redaction); nginx serves the SPA and proxies `/api` → `backend`.
- **One disk exception:** office/text conversion needs LibreOffice → isolated, immediately-deleted per-request temp dir; gated by `pdfconduit.web.office.enabled` (default true, else 415). Everything else (PDFs, images, pipeline) stays in memory.

## Key Conventions

- Core operations and stateless helpers are utility classes: `final`, `private` ctor, only static methods. Match this when adding a new operation/util.
- Every operation accepts any supported input — GUI/pipeline route non-PDF inputs through `DocumentConverter` first (no separate "convert" step). Office needs LibreOffice; its absence yields a clear message, not a crash. Prefer `service/OperationRunner` (`runSingle`/`runBatch`) over re-implementing convert→run→name→cleanup per surface.
- An operation's identity lives once in `service/OperationType` — read the suffix from `OperationType.X.suffix()`, never hard-code `"_compressed"`. `BasePanel`, CLI and `NodeKind` all derive from it. Guarded by `MessagesParityTest`, `SidebarItemCatalogTest`, `NodeKindCatalogTest`.
- `PdfCompressor` does a **lossless pass first** (re-save with object-stream compression, drops orphaned objects); stops there if it meets target or the PDF has no images. Else walks a `(scale, quality)` ladder gentlest-first — **full-res rungs (scale 1.0, decreasing JPEG quality) before any downscaling** (`0.75 → 0.5 → 0.33`), estimating via `SizeEstimator`, early-stopping at target; if unreachable saves the smallest result (never larger than input).
- GUI panels are lazily instantiated (`computeIfAbsent`) and cached in `MainWindow`.
- Pipeline **model + executor live in `core.pipeline`, JavaFX-free** (headless unit tests: `PipelineExecutorTest`, `PipelineValidatorTest`, `PipelineStoreTest`); the canvas (`app.gui.pipeline`) is the JavaFX layer. Keep new pipeline logic in `core.pipeline`. A new node kind touches: `service/OperationType`, `NodeKind` (label + catalog mapping), `PipelineNode` fields, the `PipelineExecutor` switch, exhaustive switches in `Icons.of(NodeKind)` + `NodeView.refreshSummary`, `NodeInspector`, `PipelineView` palette, and `kind.*` i18n keys.
- Adding a **sidebar operation** touches: a `SidebarItem` value, `Icons.of(SidebarItem)`, a `MainWindow.createPanel` case, the panel, and i18n keys in all `messages*.properties`.
- CSS theming: `base.css` holds shared styles; each theme (`light`, `dark`, `nord`, `dracula`, `solarized`, `sunset`) imports it and overrides variables; scene stylesheet list replaced wholesale on theme change. `StylesheetParseTest` guards every theme parses.
- i18n: add each key to **all** `messages*.properties` (`MessagesParityTest` fails otherwise). Use `I18n.t(key, args...)` for lookups, `I18n.bindText(setter, key, args...)` for live-retranslating labels.
- **`docs/` is in `.gitignore` — design specs/plans/session notes there are NOT committed. Do not add Co-Authored-By trailers to commits in this repo.**
