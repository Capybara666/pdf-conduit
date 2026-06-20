<p align="center"><img src="assets/logo.svg" alt="PDF Conduit" width="96"></p>

# PDF Conduit — User Guide

A practical walkthrough of everything PDF Conduit can do, for both the graphical
app and the command line. For a project overview and build instructions see the
[README](README.md).

## Contents

- [Installing & launching](#installing--launching)
- [Working with files](#working-with-files)
- [The operations](#the-operations)
- [Batch mode](#batch-mode)
- [The Wizard](#the-wizard)
- [Pipelines](#pipelines)
- [Command line (CLI)](#command-line-cli)
- [Syntax reference](#syntax-reference)
- [Languages, themes & sound](#languages-themes--sound)
- [Where files are saved](#where-files-are-saved)
- [Troubleshooting](#troubleshooting)

## Installing & launching

PDF Conduit ships as a self-contained app (it bundles its own Java runtime — you
don't need to install Java separately):

- **Linux** — run the portable app-image, or install the `.deb` package.
- **Windows** — run the portable app-image, or install with the `.exe` installer.

Launching with **no arguments** opens the graphical app. Launching with
**arguments** runs the [command line](#command-line-cli) instead. The window opens
centred on the monitor your mouse is on.

> **Office & text documents** (`.docx`, `.odt`, `.rtf`, `.xlsx`, `.pptx`, `.txt`, …)
> are converted to PDF through **LibreOffice**. This is optional — PDFs and images
> work without it — but if you want to feed Office documents into any operation,
> install LibreOffice (the `soffice` command) first. Without it, those files
> report a clear "LibreOffice is not installed" message.

## Working with files

Every operation screen accepts files the same way:

- **Drag and drop** files onto the drop zone, or click it to open a file picker.
- Files appear in a **list** you can reorder (drag) or remove.
- You can mix **PDFs, images, and Office documents** — non-PDF inputs are converted
  to PDF automatically, so you never need a separate conversion step.
- Set the **output** path (a file or a folder, depending on the operation), then
  run. A progress bar reports long operations.

## The operations

| Operation | What it does |
|-----------|--------------|
| **Merge** | Combine several PDFs/images/documents into **one** PDF, in list order. |
| **Extract** | Pull out selected pages — into one PDF, or one file *per page* (Separate mode). |
| **Compress** | Shrink a PDF towards a **target file size** by downsampling its images. |
| **Rotate** | Rotate selected pages by 90°, 180° or 270°. |
| **Arrange** | Reorder pages, and optionally duplicate or drop them. |
| **To PDF** | Convert each input to its **own** PDF (images placed at a chosen page size). |
| **Protect** | Add a password (AES-128) so the PDF can't be opened without it. |
| **Unlock** | Remove a password from a protected PDF (you must supply the current one). |
| **Metadata** | View, edit, or strip the title / author / subject / keywords. |
| **Watermark** | Stamp text or an image/logo across every page (opacity, rotation). |

A few details worth knowing:

- **Compress** tries progressively stronger settings and stops as soon as the
  estimate meets your target. If the target can't be reached, it saves the
  smallest version it managed (and never produces a file larger than the original).
- **Extract → Separate files** writes one PDF per page into a folder, named after
  the source (e.g. `report_p01.pdf`, `report_p02.pdf`, …).
- **Arrange** takes a page order such as `3,1,2`. Use `5-1` to reverse a run, and
  repeat a page number to duplicate it. Leave it blank to keep the natural order.
- **To PDF** keeps inputs separate (one PDF each). To combine images into a single
  PDF, use **Merge** instead.

## Batch mode

The per-file operations — **Extract, Compress, Rotate, To PDF** — run in **batch
mode**: select many files and each one is processed independently into an output
**folder**. This is the quickest way to, say, compress a whole folder of scans or
rotate a stack of documents in one go.

## The Wizard

The **Wizard** guides you through a complete *merge → arrange → compress → export*
flow in five steps:

1. **Select files** — pick the PDFs/images/documents to combine.
2. **Arrange pages** — visually reorder (and drop/duplicate) pages.
3. **Page settings** — choose page size for image inputs.
4. **Compression** — optionally compress to a target size.
5. **Export** — choose where to save and run it.

Use the Wizard when you want one polished PDF from several inputs without wiring
anything up yourself.

## Pipelines

The **Pipeline** screen is a visual node editor for chaining operations:

1. Drag **source** and **operation** blocks onto the canvas.
2. Wire a block's output into the next block's input.
3. Press run — the whole graph executes in dependency order.

Edges carry **bundles** of documents:

- **Map** operations (Extract, Compress, Rotate, Arrange, To PDF) apply to *each*
  document in the bundle and pass a same-size bundle along.
- **Merge** is the only **reduce** operation — it collapses a whole bundle into a
  single PDF.

Each terminal (output) block writes its results to the destination you set on it —
a `.pdf` file for a single result, or a folder when several files are produced.
Pipelines validate before running (no empty sources, no cycles, every output has a
destination), and "Extract → Separate files" must be the last step in its chain.

**Save / Load.** Use the **Save** and **Load** buttons to store a pipeline as a
`.json` file and reopen it later. A saved pipeline can also be run from the command
line without opening the app:

```bash
pdf-conduit pipeline my-pipeline.json
```

## Command line (CLI)

Running the app with arguments invokes the CLI (the launcher is `pdf-conduit`):

```bash
pdf-conduit merge a.pdf b.pdf images/*.png -o combined.pdf
pdf-conduit split report.pdf --pages 1-3,5,end-2 -o pages.pdf
pdf-conduit split report.pdf --separate -o pages/          # one PDF per page into a folder
pdf-conduit compress scan.pdf --target-size 5MB -o smaller.pdf
pdf-conduit rotate doc.pdf --pages 1,3 --angle 90 -o rotated.pdf
pdf-conduit arrange doc.pdf --order 3,1,2 -o reordered.pdf  # 5-1 reverses; repeat to duplicate
pdf-conduit to-pdf *.jpg --page-size A4 -o album.pdf        # alias of images-to-pdf
pdf-conduit protect secret.pdf --password s3cret -o locked.pdf
pdf-conduit unlock locked.pdf --password s3cret -o open.pdf
pdf-conduit metadata report.pdf --show
pdf-conduit metadata report.pdf --title "Q3 Report" --author Me -o tagged.pdf
pdf-conduit metadata report.pdf --strip -o clean.pdf
pdf-conduit watermark report.pdf --text DRAFT --opacity 0.3 --scale 0.9 -o stamped.pdf
pdf-conduit watermark report.pdf --image logo.png --rotation 0 -o branded.pdf  # --scale 0.05-2 sizes it
pdf-conduit pipeline my-pipeline.json   # run a pipeline saved from the GUI
```

Run `pdf-conduit --help`, or `pdf-conduit <command> --help`, for the full options
of any command.

**Exit codes:** `0` success · `1` bad input (e.g. an invalid page range) ·
`2` the operation failed.

## Syntax reference

- **Page ranges** (`--pages`): `1`, `2-5`, `1,3,5-8`, `end-2` (counted back from the
  last page). Combine freely, e.g. `1,3,5-8,end-2`.
- **Page order** (`arrange --order`): a list like `3,1,2`. A descending run such as
  `5-1` reverses; repeating a number duplicates that page; omitting a number drops
  it. Blank keeps the original order.
- **Target sizes** (`--target-size`): `500KB`, `5MB`, `1.5MB`.
- **Page sizes** (`--page-size`, for image inputs): `FIT` (match the image), `A4`,
  `A3`, `LETTER`.

## Languages, themes & sound

From the menu bar in the GUI:

- **Language** — English, Polski, Español, 中文. Switching is live and **never**
  discards your loaded files or in-progress work.
- **Theme** — Daylight, Graphite, Nord, Dracula, Solarized, Sunset, plus a
  **System** option that follows your OS light/dark setting.
- **Sound** — toggle UI sound effects.

Your language, theme and window size are remembered between sessions.

## Where files are saved

When you don't pick an explicit location, the GUI defaults to a **`pdf-conduit`
folder inside your Documents directory** (`~/Documents/pdf-conduit`, or
`~/pdf-conduit` if you have no Documents folder). The default single-file result is
named `pdf_conduit_result.pdf`.

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| "LibreOffice is not installed" | Install LibreOffice (the `soffice` command) to convert Office/text documents. PDFs and images don't need it. |
| Compress didn't reach my target size | The file may have little image data to shrink (text/vector PDFs compress poorly). It still saves the smallest result it could, never larger than the original. |
| "Invalid page range" / exit code 1 | Check the [page-range syntax](#syntax-reference); numbers must be within the document's page count. |
| Office document won't convert | Make sure no modal LibreOffice dialog is blocking; conversions use a private profile and can run alongside an open LibreOffice. |
| GUI won't start from a `.pdf` double-click | The app opens the GUI only with **no** arguments; passing a file path runs the CLI. Launch it directly to open the GUI. |
