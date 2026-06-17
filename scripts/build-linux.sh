#!/usr/bin/env bash
#
# Builds a Linux release of PDF Conduit with jpackage.
#   - Always produces a portable app-image (a self-contained folder + .zip).
#   - Also attempts a .deb installer (needs `fakeroot` and `binutils`/`dpkg`).
#
# Run this ON Ubuntu/Linux — jpackage builds for the host OS only.
# Requirements: JDK 21+ (provides jpackage), Maven, zip.
#
set -euo pipefail

# Move to the repository root (parent of this script's dir).
cd "$(dirname "$0")/.."

APP_NAME="pdf-conduit"
APP_VERSION="1.0.0"            # keep in sync with pom.xml <version>
MAIN_CLASS="org.example.app.Main"
VENDOR="PDF Conduit"
ICON="assets/icon-512.png"
LIB="pdf-utils-app/target/dist-lib"
APP_JAR="pdf-utils-app-${APP_VERSION}.jar"
OUT="dist/linux"

echo "==> [1/4] Building modules and collecting runtime dependencies"
mvn -q -Pdist clean package -DskipTests

echo "==> [2/4] Staging jpackage input"
cp "pdf-utils-app/target/${APP_JAR}" "${LIB}/"
rm -rf "${OUT}"
mkdir -p "${OUT}"

echo "==> [3/4] Creating portable app-image"
jpackage \
  --type app-image \
  --name "${APP_NAME}" \
  --icon "${ICON}" \
  --app-version "${APP_VERSION}" \
  --vendor "${VENDOR}" \
  --description "Merge, extract, compress and rotate PDFs; convert images to PDF." \
  --input "${LIB}" \
  --main-jar "${APP_JAR}" \
  --main-class "${MAIN_CLASS}" \
  --dest "${OUT}"

( cd "${OUT}" && zip -qr "${APP_NAME}-${APP_VERSION}-linux.zip" "${APP_NAME}" )
echo "    portable app-image: ${OUT}/${APP_NAME}/  (zipped: ${OUT}/${APP_NAME}-${APP_VERSION}-linux.zip)"

echo "==> [4/4] Attempting .deb installer (optional)"
if jpackage \
  --type deb \
  --name "${APP_NAME}" \
  --icon "${ICON}" \
  --app-version "${APP_VERSION}" \
  --vendor "${VENDOR}" \
  --input "${LIB}" \
  --main-jar "${APP_JAR}" \
  --main-class "${MAIN_CLASS}" \
  --linux-shortcut \
  --dest "${OUT}"; then
    echo "    .deb created in ${OUT}/"
else
    echo "    WARN: .deb step failed — install 'fakeroot' and 'binutils' to enable it."
    echo "          The portable app-image and .zip above are ready to ship."
fi

echo "==> Done. Artifacts in ${OUT}/"
