<p align="center"><img src="assets/logo.svg" alt="PDF Conduit" width="112"></p>

# PDF Conduit

A small desktop toolkit for everyday PDF tasks — **merge, extract pages,
compress to a target size, rotate, and convert images to PDF** — with both a
JavaFX GUI and a command-line interface, built on [Apache PDFBox](https://pdfbox.apache.org/).

## Features

- **Core operations:** merge PDFs/images, extract a page range, compress to a
  target file size (iterative image downsampling), rotate pages, images → PDF.
- **GUI (JavaFX):**
  - A panel per operation with drag-and-drop, a file list, and live progress.
  - **Batch mode** — single-input operations (Extract/Compress/Rotate) process
    every selected file into an output folder.
  - A guided **Wizard** for the merge → arrange → compress → export flow.
  - **Pipelines** — a visual node editor: drag operation/source blocks onto a
    canvas, wire outputs into inputs, and run the whole graph. Edges carry
    bundles of documents (map operations apply per file; Merge/Images→PDF
    collapse a bundle into one).
  - Six color themes (Daylight, Graphite, Nord, Dracula, Solarized, Sunset) plus
    a System option, with subtle UI animations.
- **CLI** mirroring every core operation, with exit codes (0 success, 1 bad
  input, 2 operation failed).

## Requirements

- **JDK 21+** (the release scripts also use its bundled `jpackage`).
- **Maven 3.9+**.

## Build & test

```bash
mvn package        # build everything
mvn test           # run all tests
```

## Run the GUI

```bash
cd pdf-utils-app && mvn javafx:run
```

Launching with no arguments opens the GUI; launching with arguments runs the
CLI (see below). The window opens centered on your primary monitor.

## CLI

The application entry point dispatches to the CLI when given arguments. Once you
build a native package (see *Releases*), the launcher is `pdf-conduit`:

```bash
pdf-conduit merge a.pdf b.pdf images/*.png -o combined.pdf
pdf-conduit split report.pdf --pages 1-3,5,end-2 -o pages.pdf
pdf-conduit compress scan.pdf --target-size 5MB -o smaller.pdf
pdf-conduit rotate doc.pdf --pages 1,3 --angle 90 -o rotated.pdf
pdf-conduit images-to-pdf *.jpg --page-size A4 -o album.pdf
```

- **Page ranges:** `1`, `2-5`, `1,3,5-8`, `end-2` (relative to the last page).
- **Sizes:** `500KB`, `5MB`, `1.5MB`.

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
pdf-utils-core/   pure library — PDFBox operations, models, utils (no JavaFX/CLI)
pdf-utils-app/    entry point: CLI (picocli) + GUI (JavaFX), depends on core
scripts/          jpackage release builders
```

- `pdf-utils-core` is dependency-light and headlessly testable.
- The pipeline **model + executor** (`pdf-utils-app/.../pipeline`) are
  JavaFX-free and unit-tested; the canvas is the JavaFX layer on top.

## Testing

```bash
mvn test -pl pdf-utils-core                       # core operations
mvn test -pl pdf-utils-app                         # CLI + pipeline + stylesheet tests
mvn test -pl pdf-utils-core -Dtest=PdfMergerTest   # a single class
```

GUI interactions (canvas dragging, animations) are verified manually with
`mvn javafx:run`; everything else is covered by automated tests.
