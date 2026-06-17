# Builds a Windows release of PDF Conduit with jpackage.
#   - Always produces a portable app-image (a self-contained folder + .zip).
#   - Also attempts an .exe installer (needs the WiX Toolset 3.x on PATH).
#
# Run this ON Windows — jpackage builds for the host OS only.
# Requirements: JDK 21+ (provides jpackage), Maven.  WiX only for the .exe step.
#
# If script execution is blocked, run once:
#   powershell -ExecutionPolicy Bypass -File scripts\build-windows.ps1

$ErrorActionPreference = "Stop"

# Move to the repository root (parent of this script's dir).
Set-Location (Join-Path $PSScriptRoot "..")

$AppName    = "pdf-conduit"
$AppVersion = "1.0.0"          # keep in sync with pom.xml <version>
$MainClass  = "org.example.app.Main"
$Vendor     = "PDF Conduit"
$Lib        = "pdf-utils-app\target\dist-lib"
$AppJar     = "pdf-utils-app-$AppVersion.jar"
$Out        = "dist\windows"

Write-Host "==> [1/4] Building modules and collecting runtime dependencies"
mvn -q -Pdist clean package "-DskipTests"
if ($LASTEXITCODE -ne 0) { throw "Maven build failed." }

Write-Host "==> [2/4] Staging jpackage input"
Copy-Item "pdf-utils-app\target\$AppJar" $Lib -Force
if (Test-Path $Out) { Remove-Item -Recurse -Force $Out }
New-Item -ItemType Directory -Force -Path $Out | Out-Null

Write-Host "==> [3/4] Creating portable app-image"
jpackage `
  --type app-image `
  --name $AppName `
  --app-version $AppVersion `
  --vendor $Vendor `
  --description "Merge, extract, compress and rotate PDFs; convert images to PDF." `
  --input $Lib `
  --main-jar $AppJar `
  --main-class $MainClass `
  --dest $Out
if ($LASTEXITCODE -ne 0) { throw "jpackage app-image failed." }

Compress-Archive -Path "$Out\$AppName" -DestinationPath "$Out\$AppName-$AppVersion-windows.zip" -Force
Write-Host "    portable app-image: $Out\$AppName\  (zipped: $Out\$AppName-$AppVersion-windows.zip)"

Write-Host "==> [4/4] Attempting .exe installer (optional, needs WiX Toolset)"
jpackage `
  --type exe `
  --name $AppName `
  --app-version $AppVersion `
  --vendor $Vendor `
  --input $Lib `
  --main-jar $AppJar `
  --main-class $MainClass `
  --win-dir-chooser `
  --win-menu `
  --win-shortcut `
  --dest $Out
if ($LASTEXITCODE -ne 0) {
    Write-Warning "  .exe step failed - install the WiX Toolset 3.x (https://wixtoolset.org) to enable it."
    Write-Warning "  The portable app-image and .zip above are ready to ship."
}

Write-Host "==> Done. Artifacts in $Out\"
