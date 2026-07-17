/**
 * TypeScript mirror of the backend `com.pdfconduit.core.pipeline` JSON shapes.
 * The backend (de)serialises by field name (Gson), so these property names are
 * load-bearing — they must match `PipelineModel` / `PipelineNode` / `Connection`
 * exactly. Field defaults mirror `PipelineNode`; we only emit the fields a node
 * kind actually uses, and the backend fills the rest with its own defaults.
 */

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
  | 'TO_IMAGES'
  | 'TO_TEXT';

export type SplitModeName = 'COMBINE' | 'SEPARATE';
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

/** Entry from `GET /api/pipeline/kinds` (best-effort; we fall back if absent). */
export interface NodeKindInfo {
  name: NodeKindName;
  label: string;
  /** REDUCE collapses a bundle to one output; MAP is 1:1. Source has neither. */
  cardinality?: 'MAP' | 'REDUCE';
  source?: boolean;
  export?: boolean;
}

/** Validation error from `POST /api/pipeline/validate`. */
export interface PipelineValidationError {
  nodeId?: string;
  message: string;
}
