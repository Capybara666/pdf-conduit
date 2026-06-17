# Release builds

Native, self-contained desktop bundles built with **jpackage**. Each script must
be run **on the target OS** — jpackage cannot cross-compile; it bundles a runtime
for the host platform only.

| Target  | Script                       | Run on   |
|---------|------------------------------|----------|
| Linux   | `scripts/build-linux.sh`     | Ubuntu/Linux |
| Windows | `scripts/build-windows.ps1`  | Windows  |

Output goes to `dist/linux/` or `dist/windows/` (both git-ignored).

## What you get

Each script always produces a **portable app-image** — a self-contained folder
(with its own Java runtime, no JDK needed by the user) plus a `.zip` of it:

- Linux: `dist/linux/pdf-conduit/bin/pdf-conduit`
- Windows: `dist/windows/pdf-conduit/pdf-conduit.exe`

Each script then *attempts* a native installer (non-fatal if tooling is missing):

- Linux `.deb` — needs `fakeroot` and `binutils`/`dpkg` (`sudo apt install fakeroot binutils`).
- Windows `.exe` — needs the [WiX Toolset 3.x](https://wixtoolset.org) on `PATH`.

## Requirements

- **JDK 21+** (provides `jpackage` and `jlink`) on `PATH`.
- **Maven** on `PATH`.
- Linux also needs `zip`; Windows uses built-in `Compress-Archive`.

## How it works

1. `mvn -Pdist clean package` builds the modules and, via the `dist` profile,
   copies every runtime dependency — including the platform-specific JavaFX
   native jars resolved for the host OS — into `pdf-utils-app/target/dist-lib/`.
2. The app jar is staged alongside those dependencies.
3. `jpackage` bundles a trimmed Java runtime + all jars and generates the
   launcher. The entry point is `org.example.app.Main` (a plain class, not a
   JavaFX `Application`), so the app launches correctly from the classpath.

## Versioning

The app version is hard-coded as `1.0.0` in both scripts; keep it in sync with
`<version>` in the root `pom.xml`.
