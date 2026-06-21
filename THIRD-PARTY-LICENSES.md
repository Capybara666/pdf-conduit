# Third-Party Licenses

PDF Conduit is distributed with the open-source components listed below. Each is
the property of its respective copyright holders and is used under the stated
license. Full license texts are in the [`licenses/`](licenses/) folder; required
attribution notices are aggregated in [`NOTICE`](NOTICE).

This file covers the software **redistributed in the application bundle** (the
runtime dependencies and the bundled Java runtime). Build- and test-only tools
(e.g. JUnit, Maven plugins) are not redistributed and are not listed here.

| Component | Version | License | Copyright |
|---|---|---|---|
| Apache PDFBox (`pdfbox`, `fontbox`, `pdfbox-io`) | 3.0.3 | Apache-2.0 | © The Apache Software Foundation |
| Apache Commons Logging (`commons-logging`) | 1.3.3 | Apache-2.0 | © The Apache Software Foundation |
| Google Gson (`gson`) | 2.11.0 | Apache-2.0 | © Google Inc. |
| Google Error Prone Annotations (`error_prone_annotations`) | 2.27.0 | Apache-2.0 | © Google LLC |
| picocli (`picocli`) | 4.7.6 | Apache-2.0 | © Remko Popma |
| TwelveMonkeys ImageIO (`imageio-webp`, `imageio-core`, `imageio-metadata`, `common-image`, `common-io`, `common-lang`) | 3.12.0 | BSD-3-Clause | © 2008–2020 Harald Kuhr |
| OpenJFX / JavaFX (`javafx-base`, `javafx-graphics`, `javafx-controls`, and platform-native artifacts) | 21.0.6 | GPL-2.0 **with Classpath Exception** | © Oracle and/or its affiliates |
| Bundled Java runtime (OpenJDK, included by `jpackage`) | per build JDK | GPL-2.0 **with Classpath Exception** | © Oracle and/or its affiliates |

## License texts

- Apache-2.0 — [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt)
- BSD-3-Clause — [`licenses/BSD-3-Clause.txt`](licenses/BSD-3-Clause.txt)
- GPL-2.0 with Classpath Exception — [`licenses/GPL-2.0-with-Classpath-Exception.txt`](licenses/GPL-2.0-with-Classpath-Exception.txt)

## Notes on compliance

- **Apache-2.0** (PDFBox, Commons Logging, Gson, Error Prone, picocli) — permits
  redistribution within a proprietary product. We retain the license text and
  reproduce the upstream attribution notices in `NOTICE` (§4(d)).
- **BSD-3-Clause** (TwelveMonkeys) — permits redistribution; we retain the
  copyright notice, the list of conditions and the disclaimer (in
  `licenses/BSD-3-Clause.txt`). The "no endorsement" clause is observed.
- **GPL-2.0 with Classpath Exception** (OpenJFX and the bundled OpenJDK runtime) —
  the Classpath Exception is precisely what lets these libraries be linked with
  PDF Conduit's independent, proprietary modules and distributed under our own
  terms. We ship the full GPLv2 text and the exception clause. If you build the
  native bundles, the bundled JDK is itself a GPLv2+CE OpenJDK build; its own
  `legal/` directory ships inside the produced app image.

## Source code for GPL-licensed components (written offer)

The Classpath Exception lets us keep PDF Conduit's own code proprietary, but the
GPLv2-licensed components we **redistribute** — OpenJFX and the bundled OpenJDK
runtime — still carry GPLv2's source-availability obligation. We satisfy it as
follows.

The OpenJFX libraries and the bundled OpenJDK runtime shipped with PDF Conduit are
**unmodified** official builds. Their complete corresponding source code, matching
the exact versions distributed, is publicly available at:

- **OpenJFX 21.0.6** — <https://github.com/openjdk/jfx>, tag `21.0.6` (the
  `org.openjfx` binaries are produced by Gluon from this source:
  <https://github.com/gluonhq/jfx>).
- **Bundled OpenJDK runtime** — the runtime is the OpenJDK build used to run
  `jpackage` at release time. Record its exact vendor and version here at release
  and link its matching source, e.g.:
  - Eclipse Temurin / Adoptium → <https://github.com/adoptium/jdk21u>
  - Oracle OpenJDK → <https://github.com/openjdk/jdk21u>
  - **[RELEASE: bundled JDK vendor + exact version, e.g. "Temurin 21.0.x+y"]**

**Written offer.** For at least three (3) years from the date you receive a copy of
PDF Conduit, [LICENSOR LEGAL NAME] will, on request, provide the complete
corresponding source code for the GPLv2-licensed components it distributes
(OpenJFX and the bundled OpenJDK runtime), on a physical medium, for a charge no
more than our cost of physically performing the distribution. Send requests to
**[SOURCE-REQUEST EMAIL]**. This offer is valid to anyone in receipt of this
information. Where the upstream links above already provide the matching source,
that satisfies the same obligation.

> Note: this written offer must name the **exact** bundled JDK version per release
> (it changes with the build machine's JDK). Update the bracketed placeholder and
> the contact email above as part of each release checklist.

> This file documents the licenses of bundled dependencies. It is **not** legal
> advice. Before a commercial release, have counsel confirm the redistribution
> terms — especially anything you add later that is GPL/LGPL **without** a linking
> exception, which would impose stronger obligations.
