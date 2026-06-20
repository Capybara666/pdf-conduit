# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build everything
mvn package

# Run all tests
mvn test

# Run tests for a single module
mvn test -pl pdf-utils-core
mvn test -pl pdf-utils-app

# Run a single test class
mvn test -pl pdf-utils-core -Dtest=PdfMergerTest
mvn test -pl pdf-utils-app -Dtest=CliIntegrationTest

# Launch the GUI (JavaFX)
cd pdf-utils-app && mvn javafx:run

# Build platform packages
mvn package -P linux    # → AppImage
mvn package -P windows  # → .exe installer
```

## Architecture

Two-module Maven project, Java 21, targeting Java 24.

**`pdf-utils-core`** — pure library, no JavaFX, no picocli. Only depends on Apache PDFBox 3.x.
- `operations/` — stateless classes (`PdfMerger`, `PdfSplitter`, `PdfCompressor`, `PdfRotator`, `ImageToPdfConverter`), each with a single `execute(Options)` method returning a typed result.
- `model/` — records for options and results; `PageSource` is a sealed interface with two permitted records: `PdfPageSource(Path, PageRange)` and `ImageSource(Path, PageSize)`.
- `util/` — `PageRangeParser` (syntax: `1`, `2-5`, `1,3,5-8`, `end-2`), `FileTypeDetector`, `SizeEstimator`.
- `exception/` — checked: `PdfOperationException`, `InvalidPageRangeException`, `TargetSizeUnreachableException`.

**`pdf-utils-app`** — entry point for both GUI and CLI, depends on core.
- `Main.java` — dispatches: args present → picocli `RootCommand`; no args → `GuiLauncher` (JavaFX).
- `cli/` — picocli subcommands mirroring each core operation; `SizeConverter` handles `500KB`/`5MB`/`1.5MB` syntax. Exit codes: 0 success, 1 bad input, 2 operation failed.
- `gui/` — JavaFX. `MainWindow` owns a sidebar (`SidebarController`) and a `StackPane` content area that swaps between panels and wizard.
  - `panels/` — `BasePanel` (abstract `VBox`) provides shared DropZone + FileListView + output path + ProgressPanel layout; each operation subclasses it.
  - `wizard/` — `WizardController` owns `WizardModel` (shared state across steps) and drives five `WizardStep` implementations. Step indicator is built programmatically (no FXML).
  - `component/` — `DropZone`, `FileListView`, `ProgressPanel` are reusable custom controls.
  - `ThemeManager` — reads/writes `java.util.prefs.Preferences`; detects OS theme via `gsettings` (Linux) or registry (Windows); applies `/css/dark.css` or `/css/light.css` to the scene.

## Key Conventions

- Core operations are stateless and thread-safe by design — no instance fields, only method-local state.
- `PdfCompressor` uses iterative downsampling: DPI levels `[300, 200, 150, 96]` × quality levels `[0.9, 0.7, 0.5, 0.3]`. It checks estimated size via `SizeEstimator` after each step and stops early when the target is met.
- GUI panels are instantiated lazily (`computeIfAbsent`) and cached in `MainWindow`; re-selecting a sidebar item returns the same panel instance.
- CSS theming: `base.css` contains shared styles; `light.css` and `dark.css` each import `base.css` and override variables. The scene stylesheet list is replaced wholesale on theme change.
- `docs/` is in `.gitignore` — design specs and plans there are not committed.