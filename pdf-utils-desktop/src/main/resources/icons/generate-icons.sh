#!/usr/bin/env bash
# Regenerate the PDF Conduit desktop app icons from app-icon.svg (the Nodegraph mark).
#
# Rasterizer preference: rsvg-convert > headless Chrome/Chromium (supersampled then
# downscaled with ImageMagick for crisp small sizes). Transparent background.
#
# Usage:  ./generate-icons.sh
set -euo pipefail
cd "$(dirname "$0")"
SRC="app-icon.svg"
SIZES=(16 24 32 48 64 128 256)

have() { command -v "$1" >/dev/null 2>&1; }

CHROME=""
for c in google-chrome-stable google-chrome chromium chromium-browser; do
  if have "$c"; then CHROME="$c"; break; fi
done

render_chrome() { # $1=size $2=out
  local size="$1" out="$2" ss=$(( $1 * 8 )); (( ss < 256 )) && ss=256
  local html; html="$(mktemp --suffix=.html)"
  cat > "$html" <<EOF
<!doctype html><html><head><meta charset="utf-8"><style>
*{margin:0;padding:0}html,body{background:transparent}
img{display:block;width:${ss}px;height:${ss}px}
</style></head><body><img src="file://$(pwd)/$SRC"></body></html>
EOF
  local big; big="$(mktemp --suffix=.png)"
  "$CHROME" --headless=new --disable-gpu --no-sandbox --hide-scrollbars \
    --default-background-color=00000000 --force-device-scale-factor=1 \
    --window-size="${ss},${ss}" --screenshot="$big" "file://$html" >/dev/null 2>&1
  if have magick; then magick "$big" -resize "${size}x${size}" "$out"
  else convert "$big" -resize "${size}x${size}" "$out"; fi
  rm -f "$html" "$big"
}

for s in "${SIZES[@]}"; do
  out="app-${s}.png"
  if have rsvg-convert; then
    rsvg-convert -w "$s" -h "$s" -o "$out" "$SRC"
  elif [ -n "$CHROME" ]; then
    render_chrome "$s" "$out"
  else
    echo "No rasterizer found (need rsvg-convert or Chrome/Chromium)." >&2
    exit 1
  fi
  echo "wrote $out"
done
