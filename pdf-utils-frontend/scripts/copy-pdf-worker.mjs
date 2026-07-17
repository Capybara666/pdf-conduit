/**
 * Copies the version-pinned pdf.js worker into `public/` so Angular serves it
 * verbatim at the app root (never a CDN). Run automatically before every build
 * (see package.json `prebuild`) so the worker always matches the installed
 * pdfjs-dist version.
 */
import { copyFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..');
const src = join(root, 'node_modules', 'pdfjs-dist', 'legacy', 'build', 'pdf.worker.min.mjs');
const destDir = join(root, 'public');
const dest = join(destDir, 'pdf.worker.min.mjs');

mkdirSync(destDir, { recursive: true });
copyFileSync(src, dest);
console.log(`[copy-pdf-worker] ${src} -> ${dest}`);
