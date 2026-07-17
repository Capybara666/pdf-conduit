# Summary of changes

This batch covers three requests: (1) center the window on the main monitor,
(2) add release build scripts for Ubuntu and Windows, and (3) a functional
review with usability improvements.

---

## 1. Window opens centered on the main monitor

`MainWindow.show()` now positions the window in the center of the **primary**
monitor's visual bounds, computed after `stage.show()` so the real (decorated)
window size is known.

- File: `pdf-utils-desktop/.../gui/MainWindow.java`

---

## 2. Release build scripts (Ubuntu + Windows)

There was a mismatch: `CLAUDE.md` claimed `mvn package -P linux/-P windows`
produced an AppImage/.exe, but **no such Maven profiles or jpackage config
existed**. I built a real packaging pipeline instead.

- **New Maven profile `dist`** (`pdf-utils-desktop/pom.xml`) collects every runtime
  dependency — including the platform-specific JavaFX native jars resolved for
  the build OS — into `target/dist-lib/`. Verified it bundles the JavaFX
  `-linux` natives + the core jar.
- **`scripts/build-linux.sh`** — builds a portable app-image (+ `.zip`) and
  attempts a `.deb`.
- **`scripts/build-windows.ps1`** — builds a portable app-image (+ `.zip`) and
  attempts an `.exe` (WiX).
- **`scripts/README.md`** — prerequisites and usage.

The entry point bundled is `com.pdfconduit.app.Main` (a plain class, not a JavaFX
`Application`), so the app launches correctly from the classpath bundle.

> Not run here, as requested — jpackage must run on each target OS. I did
> validate the `mvn -Pdist package` step (dependency collection) succeeds.
> Requirements: JDK 21+, Maven; `.deb` needs `fakeroot`+`binutils`, `.exe`
> needs WiX. See `scripts/README.md`.

Output lands in `dist/` (already git-ignored).

---

## 3. Functional review + improvements

### Bug found and fixed: wizard "Page settings" (Step 3) did nothing

The page-size chosen in Step 3 (`globalPageSize`) was never applied — image
sources were created in Step 1 with a hard-coded `FIT` and exported unchanged.
Step 5 now maps the chosen page size onto every image source at export, so the
setting actually takes effect.

- File: `pdf-utils-desktop/.../gui/wizard/Step5Export.java`

### Bug fixed: wizard compression target ignored unit changes

In Step 4, the target size in bytes was only recomputed when the number field
changed, not when the MB/KB unit changed. Both now recompute.

- File: `pdf-utils-desktop/.../gui/wizard/Step4Compression.java`

### Usability: file list toolbar (count + Clear)

Every operation panel now shows a live file count ("3 files") and a **Clear**
button to empty the list in one click (previously files had to be removed one by
one).

- File: `pdf-utils-desktop/.../gui/panels/BasePanel.java`

### Consistency: single-input panels now say so

Extract, Compress and Rotate operate on a single PDF but share the multi-file
list UI, which silently used only the first file. They now display the hint
"Only the first file in the list is used."

- Files: `BasePanel.java` (hook), `SplitPanel.java`, `CompressPanel.java`,
  `RotatePanel.java`

### Polish: About dialog

Added **Help → About** with name, version and a one-line description.

- File: `MainWindow.java`

---

## Notes / suggestions not implemented (kept out of scope)

These are observations from the review worth considering later:

- **Overwrite confirmation** — output files are silently overwritten if they
  already exist. A confirm prompt would be safer.
- **Invalid compress target feedback (panel)** — typing a non-numeric target in
  the Compress panel silently does nothing; a small inline error would help.
- **Multi-file batch for single-input ops** — Extract/Compress/Rotate could
  optionally process every file in the list rather than just the first.
- **`CLAUDE.md` packaging section is outdated** — it still references the
  non-existent `-P linux/-P windows` profiles; consider updating it to point at
  `scripts/` (left untouched as it is not tracked in git).

---

## Verification

- `mvn test` — green: **25** core + **7** app tests (incl. the headless
  `StylesheetParseTest` covering all theme stylesheets).
- `mvn -Pdist clean package` — succeeds; `target/dist-lib/` contains all runtime
  jars including the JavaFX native classifier jars.
- GUI behaviors (centering, animations, dialogs) are not covered by automated
  tests — to eyeball them run `cd pdf-utils-desktop && mvn javafx:run`.

---

# Update: responsive layout + batch operations

## Layout reworked so the file list fills the view

The operation panels were a single `VBox`, so when options/output/run controls
took space the list could collapse to ~one visible row + scrollbar. `BasePanel`
is now a `BorderPane`:

- **Top (pinned):** title, optional hint, drop zone, file toolbar.
- **Center (fills):** the file list — takes all remaining height.
- **Bottom (pinned):** options, output, run/progress.

The same top-pinned / list-fills structure was applied to the wizard's
**Step 1 (Select files)** and **Step 2 (Arrange)** for consistency.

`ProgressPanel` previously reserved vertical space for its hidden elements
(progress bar, error banner, result links), which — in the pinned-bottom layout —
squeezed the list down to a row or less in panels with options (e.g. Compress).
Those elements now bind `managed` to `visible`, so the bottom collapses to just
the Run button when idle and the list keeps the space. Default window size
bumped to 900×660.

- Files: `panels/BasePanel.java`, `component/ProgressPanel.java`,
  `gui/MainWindow.java`, `wizard/Step1SelectFiles.java`,
  `wizard/Step2ArrangePages.java`

## Batch operations for Extract / Compress / Rotate

These single-input operations previously used only the *first* file in the list.
They now process **every** file:

- With **one** file selected → output is a single **file** (as before).
- With **several** files → the output target becomes a **folder**; each input is
  processed independently and saved as `<name><suffix>.pdf` in that folder
  (e.g. `report_compressed.pdf`).

To keep the view stable, the output row never changes shape — only its **label**
("Output file:" ↔ "Output folder:"), prompt, and **browse dialog** (file-save ↔
directory-chooser) adapt to the file count. The auto-filled path adapts too, and
is only overwritten while the user hasn't typed their own. Progress shows
`Compressing 2/5…` during a batch; errors name the offending file.

Implemented generically in `BasePanel` (`supportsBatch()`, `isBatchMode()`,
`runPerFile(...)`) so the three panels each added only a one-line mode check.
Merge and Images→PDF are combine-operations (many in → one out) and are
unaffected.

- Files: `panels/BasePanel.java`, `panels/SplitPanel.java`,
  `panels/CompressPanel.java`, `panels/RotatePanel.java`

> Note: the CLI subcommands still take a single input file. Extending them with
> multi-file + `--output-dir` would mirror the GUI batch behavior — a candidate
> for a follow-up.

---

# Update: Pipelines (visual node editor)

A new **Pipeline** sidebar view: a freeform canvas where you build a graph of
operations. Design spec: `docs/superpowers/specs/2026-06-17-pipelines-design.md`.

## Model (bundles of documents)

Edges carry an ordered **bundle** of documents (each = file + type + base name):
- **Source** node = chosen files.
- **Map** ops (Extract/Compress/Rotate) apply per document: N in → N out.
- **Reduce** ops (Merge / Images→PDF) collapse the whole bundle to one document.

So a multi-file bundle flows through map ops (applied to each file) and only
collapses when a Merge is placed after it. Outputs may fan out to many inputs.

Counts and per-document types are computed **statically** (source files; map
preserves count; reduce → 1), which drives validation and the adaptive terminal
destination (1 result → file, many → folder — same pattern as the batch panels).

## Architecture

JavaFX-free, headlessly-tested **model + executor** under
`com.pdfconduit.app.pipeline`:
- `PipelineModel` / `PipelineNode` / `Connection`, `Document`, `NodeKind`.
- `PipelineGraph` — topological order + count/type propagation.
- `PipelineValidator` — acyclicity, arity, type, terminal-destination rules.
- `PipelineExecutor` — threads bundles through temp files, writes terminal
  results, cleans up; reports progress via a callback. Reuses the existing core
  operations unchanged.

JavaFX view layer under `com.pdfconduit.app.gui.pipeline`:
- `PipelineView` (toolbar + canvas + inspector + Run/validation),
  `PipelineCanvas` (drag nodes, draw wires, select/delete),
  `NodeView`, `ConnectionView`, `NodeInspector`.

## Interactions (v1)

Add source via file chooser; add operations from a menu; drag cards by the
header; connect by dragging from an output port (●) to an input port (○);
Delete removes the selected node/connection; click a node to edit it in the
inspector; Run validates (offending nodes outlined red) then executes.

## Tests

`PipelineValidatorTest` (cycle/type/destination/count propagation) and
`PipelineExecutorTest` (end-to-end on temp PDFs: merge-to-file, map-to-folder,
map→reduce chain, invalid-pipeline) — all headless. The canvas itself is
verified manually (`mvn javafx:run`).

## Scope (v1, per design)

No save/load of pipeline definitions, no zoom/undo, GUI only (no CLI).
