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
mvn test -pl pdf-utils-app

# Run a single test class
mvn test -pl pdf-utils-core -Dtest=PdfMergerTest
mvn test -pl pdf-utils-app -Dtest=CliIntegrationTest

# Launch the GUI (JavaFX)
cd pdf-utils-app && mvn javafx:run

# Build a native, self-contained package (bundled JRE, via jpackage).
# Run each on its target OS — jpackage does not cross-compile. Output → dist/.
scripts/build-linux.sh        # Linux   → portable app-image + .deb
scripts/build-windows.ps1     # Windows → portable app-image + .exe
```

The release scripts activate the Maven `dist` profile (in `pdf-utils-app/pom.xml`),
which copies the runtime dependencies — including the platform-specific JavaFX
native jars — into `target/dist-lib` for jpackage to bundle.

## Architecture

Two-module Maven project, Java 21 (`maven.compiler.source`/`target` = 21).
The product name is **PDF Conduit**; the Maven artifact ids are `pdf-utils-*`.

**`pdf-utils-core`** — pure library, no JavaFX, no picocli. Depends on Apache
PDFBox 3.x and the TwelveMonkeys `imageio-webp` reader (a pure-Java WebP decoder
auto-registered with ImageIO, so `.webp` inputs decode — stock JDK ImageIO has no
WebP reader; TIFF/BMP/GIF/PNG/JPEG it already handles).
- `operations/` — stateless utility classes (`final`, private constructor, static
  `execute(Options)`): `PdfMerger`, `PdfSplitter`, `PdfCompressor`, `PdfRotator`,
  `PdfArranger`, `ImageToPdfConverter`, `PdfProtector` (AES-128 password),
  `PdfUnlocker` (remove password). Each takes an options record and returns a
  typed result record.
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
  `SizeEstimator`, `OutputPaths`.
- `exception/` — checked: `PdfOperationException`, `InvalidPageRangeException`,
  `TargetSizeUnreachableException`.

**`pdf-utils-app`** — entry point for both GUI and CLI, depends on core.
- `Main.java` — dispatches: args present → picocli `RootCommand`; no args →
  `GuiLauncher` (JavaFX).
- `cli/` — picocli subcommands mirroring each core operation (`merge`, `split`,
  `compress`, `rotate`, `arrange`, `to-pdf`/`images-to-pdf`, `protect`, `unlock`);
  `SizeConverter`
  handles `500KB`/`5MB`/`1.5MB` syntax. `CliSources` routes each input by type
  (PDF / image / office) through `DocumentConverter`, so `merge` and `to-pdf`
  accept the same inputs as the GUI (office docs need LibreOffice). Exit codes:
  0 success, 1 bad input, 2 operation failed.
- `pipeline/` — **JavaFX-free** visual-pipeline core, fully unit-tested.
  `PipelineModel` holds `PipelineNode`s and `Connection`s; `PipelineGraph` does
  the topological order / cycle detection / output-type inference; `PipelineValidator`
  checks a graph before it runs; `PipelineExecutor` runs it, threading bundles of
  `Document`s between nodes via temp files. `NodeKind` classifies nodes as source,
  *map* (one output document per input — Extract/Compress/Rotate/Arrange/To PDF/
  Protect/Unlock) or *reduce* (collapse a whole bundle into one — Merge).
- `i18n/` — `I18n`: tiny localisation helper over `i18n/messages*.properties`
  (UTF-8). English is the base bundle; `pl`, `es`, `zh` are translations. The
  chosen language is persisted (`java.util.prefs`) and listeners re-translate the
  UI **in place** (via `I18n.bindText`) so switching language never discards the
  user's loaded files / pipeline / wizard progress.
- `gui/` — JavaFX. `MainWindow` owns a `SidebarController` (`SidebarItem` enum:
  Merge, Extract, Compress, Rotate, Arrange, To PDF, Pipeline, Wizard) and a
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
  - `pipeline/` — the JavaFX layer over `app.pipeline`: `PipelineView`,
    `PipelineCanvas`, `NodeView`, `ConnectionView`, `NodeInspector` — the visual
    node editor.
  - `component/` — reusable controls: `DropZone`, `FileListView`, `ProgressPanel`,
    `PageReorderGrid`, `PageSelectGrid`/`PageSelectDialog`, `DragReorder`.
  - `util/` — `OutputPaths`, `FileOpener`, `PdfThumbnails`, `Sfx` (sound effects).
  - `ThemeManager` — reads/writes `java.util.prefs.Preferences`; detects OS theme
    via `gsettings` (Linux) or registry (Windows); applies one of the bundled CSS
    themes to the scene.

## Key Conventions

- Core operations and stateless helpers are utility classes: `final` with a
  `private` constructor and only static methods (no instance fields). Match this
  when adding a new operation or util.
- Every operation accepts any supported input: the GUI/pipeline route non-PDF
  inputs through `DocumentConverter` first, so panels and pipeline nodes don't
  need a separate "convert to PDF" step. Office conversion needs LibreOffice; its
  absence yields a clear "LibreOffice is not installed" message rather than a crash.
- `PdfCompressor` walks a ladder of `(scale, quality)` steps, gentlest first. The
  **full-resolution** rungs (scale `1.0`, decreasing JPEG quality) come *before*
  any downscaling, so a target reachable by re-encoding alone never sacrifices
  resolution; only once those are exhausted are images shrunk (`0.75 → 0.5 →
  0.33`). Each step re-encodes images as JPEG (so even lossless images at scale
  `1.0` get smaller), estimates size via `SizeEstimator`, and stops early when the
  target is met; if the target is unreachable it saves the smallest result (never
  larger than the input).
- GUI panels are instantiated lazily (`computeIfAbsent`) and cached in
  `MainWindow`; re-selecting a sidebar item returns the same panel instance.
- The pipeline **model + executor** are deliberately JavaFX-free so they can be
  unit-tested headlessly (`PipelineExecutorTest`, `PipelineValidatorTest`); the
  canvas is the JavaFX layer on top. Keep new pipeline logic in `app.pipeline`,
  not `app.gui.pipeline`.
- CSS theming: `base.css` holds shared styles; each theme file
  (`light`, `dark`, `nord`, `dracula`, `solarized`, `sunset`) imports `base.css`
  and overrides variables. The scene stylesheet list is replaced wholesale on
  theme change. A `StylesheetParseTest` guards every theme parses.
- i18n: add a key to **all** of `messages*.properties`; `MessagesParityTest`
  fails if a translation is missing a key. Use `I18n.t(key, args...)` for lookups
  and `I18n.bindText(setter, key, args...)` for labels that must re-translate live.
- `docs/` is in `.gitignore` — design specs, plans and session notes there are not
  committed. Do not add Co-Authored-By trailers to commits in this repo.
