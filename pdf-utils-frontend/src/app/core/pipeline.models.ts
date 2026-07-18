/**
 * TypeScript mirror of the backend `com.pdfconduit.core.pipeline` JSON shapes.
 * The backend (de)serialises by field name (Gson), so these property names are
 * load-bearing — they must match `PipelineModel` / `PipelineNode` / `Connection`
 * exactly. Field defaults mirror `PipelineNode`; we only emit the fields a node
 * kind actually uses, and the backend fills the rest with its own defaults.
 *
 * DTOs returned by the API (`NodeKindInfo`, `PipelineValidationError`) are
 * re-exported from the generated `api.gen.ts` so their wire contract lives in
 * one place; the client-only editing model (`CanvasNode`, wire-node
 * (de)serialisers) stays hand-declared below.
 */
import type { components } from './api.gen';

/** Enum names must match `com.pdfconduit.core.pipeline.NodeKind`. */
export type NodeKindName =
  | 'SOURCE'
  | 'MERGE'
  | 'IMAGES_TO_PDF'
  | 'EXTRACT'
  | 'COMPRESS'
  | 'ROTATE'
  | 'ARRANGE'
  | 'PROTECT'
  | 'UNLOCK'
  | 'METADATA'
  | 'WATERMARK'
  | 'NUP'
  | 'TO_IMAGES'
  | 'TO_TEXT';

export type SplitModeName = 'COMBINE' | 'SEPARATE';
/** Must match `com.pdfconduit.core.model.NupLayout` enum names. */
export type NupLayoutName = 'TWO_UP' | 'FOUR_UP' | 'SIX_UP' | 'EIGHT_UP' | 'NINE_UP';
export type PageSizeName = 'FIT' | 'A4' | 'A3' | 'LETTER';
export type ImageFormatName = 'PNG' | 'JPEG';
export type TextFormatName = 'TXT' | 'DOCX';

/** One graph node. Mirrors `PipelineNode` fields (only relevant ones populated). */
export interface PipelineNodeJson {
  id: string;
  kind: NodeKindName;
  x: number;
  y: number;
  /** SOURCE only — uploaded file names (the backend resolves them to uploads). */
  files?: string[];
  pages?: string;
  splitMode?: SplitModeName;
  order?: string;
  angle?: number;
  targetBytes?: number;
  pageSize?: PageSizeName;
  password?: string;
  ownerPassword?: string;
  metaTitle?: string;
  metaAuthor?: string;
  metaSubject?: string;
  metaKeywords?: string;
  metaStrip?: boolean;
  wmText?: string;
  wmImage?: string;
  wmOpacity?: number;
  wmRotation?: number;
  wmScale?: number;
  nupLayout?: NupLayoutName;
  nupBooklet?: boolean;
  imageFormat?: ImageFormatName;
  imageDpi?: number;
  jpegQuality?: number;
  textFormat?: TextFormatName;
  outputDestination?: string;
}

/** A directed edge. Mirrors `Connection(fromNodeId, toNodeId)`. */
export interface ConnectionJson {
  fromNodeId: string;
  toNodeId: string;
}

/** The whole graph. Mirrors `PipelineModel { nodes, connections }`. */
export interface PipelineModelJson {
  nodes: PipelineNodeJson[];
  connections: ConnectionJson[];
}

/**
 * Entry from `GET /api/pipeline/kinds` (best-effort; we fall back if absent).
 * Mirrors the backend `NodeKindInfo` schema; `name` is re-narrowed from the
 * schema's bare `string` to the {@link NodeKindName} union the palette relies
 * on (the enum names — `isSource`/`isReduce`/`isExport` — serialise verbatim).
 */
export type NodeKindInfo = Omit<components['schemas']['NodeKindInfo'], 'name'> & {
  name: NodeKindName;
};

/** Validation error from `POST /api/pipeline/validate`. Mirrors `ValidationErrorDto`. */
export type PipelineValidationError = components['schemas']['ValidationErrorDto'];

/* ---------------------------------------------------------------------------
 * Client-only editing model for the free-form canvas builder.
 *
 * `CanvasNode` is a superset of the wire node: it keeps every possible param as
 * a friendly editing value (e.g. `targetSize` as a "5MB" string) plus the live
 * `x`,`y` drag coordinates. `toWireNode` narrows it down to the exact
 * `PipelineNodeJson` fields the backend cares about for the node's kind. These
 * fields never leave the browser except via `toWireNode`.
 * ------------------------------------------------------------------------- */

/** A node as edited on the canvas. Holds all params; only relevant ones ship. */
export interface CanvasNode {
  id: string;
  kind: NodeKindName;
  /** Real drag coordinates (stored on the wire node too — backend ignores them). */
  x: number;
  y: number;
  /** SOURCE only — basenames of uploaded files fed into the graph. */
  files: string[];
  pages: string;
  splitMode: SplitModeName;
  order: string;
  angle: number;
  targetSize: string;
  pageSize: PageSizeName;
  password: string;
  ownerPassword: string;
  metaTitle: string;
  metaAuthor: string;
  metaSubject: string;
  metaKeywords: string;
  metaStrip: boolean;
  wmText: string;
  /** Basename of an uploaded watermark image (a name reference; maps to wire `wmImage`). */
  wmImageName: string;
  wmOpacity: number;
  wmRotation: number;
  wmScale: number;
  nupLayout: NupLayoutName;
  nupBooklet: boolean;
  imageFormat: ImageFormatName;
  imageDpi: number;
  textFormat: TextFormatName;
}

/** Build a fresh `CanvasNode` of the given kind at a position, with sane defaults. */
export function newCanvasNode(id: string, kind: NodeKindName, x: number, y: number): CanvasNode {
  return {
    id,
    kind,
    x,
    y,
    files: [],
    pages: '',
    splitMode: 'COMBINE',
    order: '',
    angle: 90,
    targetSize: '5MB',
    pageSize: 'FIT',
    password: '',
    ownerPassword: '',
    metaTitle: '',
    metaAuthor: '',
    metaSubject: '',
    metaKeywords: '',
    metaStrip: false,
    wmText: '',
    wmImageName: '',
    wmOpacity: 0.3,
    wmRotation: 45,
    wmScale: 0.5,
    nupLayout: 'TWO_UP',
    nupBooklet: false,
    imageFormat: 'PNG',
    imageDpi: 150,
    textFormat: 'TXT',
  };
}

/** Narrow a `CanvasNode` to the `PipelineNodeJson` fields its kind actually uses. */
export function toWireNode(n: CanvasNode): PipelineNodeJson {
  const base: PipelineNodeJson = { id: n.id, kind: n.kind, x: Math.round(n.x), y: Math.round(n.y) };
  switch (n.kind) {
    case 'SOURCE':
      return { ...base, files: n.files };
    case 'EXTRACT':
      return { ...base, pages: n.pages, splitMode: n.splitMode };
    case 'ROTATE':
      return { ...base, pages: n.pages, angle: n.angle };
    case 'ARRANGE':
      return { ...base, order: n.order };
    case 'COMPRESS':
      return { ...base, targetBytes: parseSizeToBytes(n.targetSize) };
    case 'IMAGES_TO_PDF':
      return { ...base, pageSize: n.pageSize };
    case 'PROTECT':
      return { ...base, password: n.password, ownerPassword: n.ownerPassword };
    case 'UNLOCK':
      return { ...base, password: n.password };
    case 'METADATA':
      return {
        ...base,
        metaTitle: n.metaTitle,
        metaAuthor: n.metaAuthor,
        metaSubject: n.metaSubject,
        metaKeywords: n.metaKeywords,
        metaStrip: n.metaStrip,
      };
    case 'WATERMARK':
      return {
        ...base,
        // An uploaded image wins over text (matches the desktop pipeline); its bytes ride along as
        // a separate `nodeAssets` part matched to this name.
        wmText: n.wmImageName ? '' : n.wmText,
        wmImage: n.wmImageName,
        wmOpacity: n.wmOpacity,
        wmRotation: n.wmRotation,
        wmScale: n.wmScale,
      };
    case 'NUP':
      return { ...base, nupLayout: n.nupLayout, nupBooklet: n.nupBooklet };
    case 'TO_IMAGES':
      return { ...base, imageFormat: n.imageFormat, imageDpi: n.imageDpi };
    case 'TO_TEXT':
      return { ...base, textFormat: n.textFormat };
    default:
      return base; // MERGE has no params
  }
}

/** Rebuild a `CanvasNode` from a loaded wire node (inverse of `toWireNode`). */
export function fromWireNode(w: PipelineNodeJson): CanvasNode {
  const n = newCanvasNode(w.id, w.kind, w.x ?? 40, w.y ?? 40);
  n.files = w.files ?? [];
  if (w.pages != null) n.pages = w.pages;
  if (w.splitMode != null) n.splitMode = w.splitMode;
  if (w.order != null) n.order = w.order;
  if (w.angle != null) n.angle = w.angle;
  if (w.targetBytes != null) n.targetSize = formatBytesToSize(w.targetBytes);
  if (w.pageSize != null) n.pageSize = w.pageSize;
  if (w.password != null) n.password = w.password;
  if (w.ownerPassword != null) n.ownerPassword = w.ownerPassword;
  if (w.metaTitle != null) n.metaTitle = w.metaTitle;
  if (w.metaAuthor != null) n.metaAuthor = w.metaAuthor;
  if (w.metaSubject != null) n.metaSubject = w.metaSubject;
  if (w.metaKeywords != null) n.metaKeywords = w.metaKeywords;
  if (w.metaStrip != null) n.metaStrip = w.metaStrip;
  if (w.wmText != null) n.wmText = w.wmText;
  if (w.wmImage != null) n.wmImageName = w.wmImage;
  if (w.wmOpacity != null) n.wmOpacity = w.wmOpacity;
  if (w.wmRotation != null) n.wmRotation = w.wmRotation;
  if (w.wmScale != null) n.wmScale = w.wmScale;
  if (w.nupLayout != null) n.nupLayout = w.nupLayout;
  if (w.nupBooklet != null) n.nupBooklet = w.nupBooklet;
  if (w.imageFormat != null) n.imageFormat = w.imageFormat;
  if (w.imageDpi != null) n.imageDpi = w.imageDpi;
  if (w.textFormat != null) n.textFormat = w.textFormat;
  return n;
}

/** Parse "5MB"/"800KB"/"1234" into bytes (mirrors the CLI SizeConverter). */
export function parseSizeToBytes(text: string): number {
  const m = /^\s*(\d+(?:\.\d+)?)\s*(b|kb|mb|gb)?\s*$/i.exec(text ?? '');
  if (!m) return 5 * 1024 * 1024;
  const value = parseFloat(m[1]);
  const unit = (m[2] ?? 'b').toLowerCase();
  const factor = unit === 'gb' ? 1024 ** 3 : unit === 'mb' ? 1024 ** 2 : unit === 'kb' ? 1024 : 1;
  return Math.round(value * factor);
}

/** Format bytes back into a compact "5MB"/"800KB" string for the size input. */
export function formatBytesToSize(bytes: number): string {
  if (bytes >= 1024 ** 2 && bytes % (1024 ** 2) === 0) return `${bytes / 1024 ** 2}MB`;
  if (bytes >= 1024 && bytes % 1024 === 0) return `${bytes / 1024}KB`;
  if (bytes >= 1024 ** 2) return `${(bytes / 1024 ** 2).toFixed(1)}MB`;
  if (bytes >= 1024) return `${Math.round(bytes / 1024)}KB`;
  return `${bytes}`;
}
