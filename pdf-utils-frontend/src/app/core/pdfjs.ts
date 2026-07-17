/**
 * Central pdf.js configuration. We use the *legacy* build so the bundle runs on
 * a wide range of browsers (it is transpiled + polyfilled), and we point the
 * worker at the locally-bundled module URL (never a CDN) via `import.meta.url`
 * so Angular's esbuild pipeline emits and fingerprints the worker asset.
 */
import { GlobalWorkerOptions, getDocument, type PDFDocumentProxy } from 'pdfjs-dist/legacy/build/pdf.mjs';

let configured = false;

function ensureWorker(): void {
  if (configured) return;
  // The worker is copied into the app root by scripts/copy-pdf-worker.mjs and
  // served locally (never a CDN). Resolve it against the document base href so
  // it works under any deploy path (dev server, nginx root, sub-path).
  const base = typeof document !== 'undefined' ? document.baseURI : '/';
  GlobalWorkerOptions.workerSrc = new URL('pdf.worker.min.mjs', base).toString();
  configured = true;
}

/** Load a PDF from raw bytes into a pdf.js document proxy. */
export async function loadPdf(data: ArrayBuffer | Uint8Array): Promise<PDFDocumentProxy> {
  ensureWorker();
  // pdf.js takes ownership of the buffer, so hand it a fresh copy.
  const bytes = data instanceof Uint8Array ? data : new Uint8Array(data);
  const task = getDocument({ data: bytes.slice() });
  return task.promise;
}

export type { PDFDocumentProxy };
