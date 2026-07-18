import { NAV_ITEMS, OP_ICONS } from './operations';
import { NodeKindName } from './pipeline.models';

/**
 * Coverage guard for the canonical icon registry. Every pipeline NodeKind and
 * every navigable operation id MUST resolve to a non-empty SVG path in
 * `OP_ICONS`, so a newly added node/operation can never silently fall back to a
 * blank or emoji glyph.
 *
 * `KIND_TO_OP` mirrors the maps in `pipeline-node.component.ts` and
 * `pipeline.page.ts` (NodeKind → operation id); SOURCE maps to the pipeline-only
 * `source` glyph.
 */
const KIND_TO_OP: Record<Exclude<NodeKindName, 'SOURCE'>, string> = {
  MERGE: 'merge',
  IMAGES_TO_PDF: 'to-pdf',
  EXTRACT: 'extract',
  COMPRESS: 'compress',
  ROTATE: 'rotate',
  ARRANGE: 'arrange',
  PROTECT: 'protect',
  UNLOCK: 'unlock',
  METADATA: 'metadata',
  WATERMARK: 'watermark',
  TO_IMAGES: 'to-images',
  TO_TEXT: 'to-text',
};

const ALL_KINDS: NodeKindName[] = [
  'SOURCE',
  'MERGE',
  'IMAGES_TO_PDF',
  'EXTRACT',
  'COMPRESS',
  'ROTATE',
  'ARRANGE',
  'PROTECT',
  'UNLOCK',
  'METADATA',
  'WATERMARK',
  'TO_IMAGES',
  'TO_TEXT',
];

function iconFor(kind: NodeKindName): string {
  const opId = kind === 'SOURCE' ? 'source' : KIND_TO_OP[kind];
  return OP_ICONS[opId] ?? '';
}

describe('OP_ICONS registry', () => {
  it('resolves a non-empty SVG path for every NodeKind', () => {
    for (const kind of ALL_KINDS) {
      const d = iconFor(kind);
      expect(d.length).withContext(`missing icon for NodeKind ${kind}`).toBeGreaterThan(0);
      // Guard against a stray emoji fallback ever creeping back in: paths start with a move command.
      expect(d.trim().charAt(0)).withContext(`icon for ${kind} is not an SVG path`).toBe('M');
    }
  });

  it('resolves a non-empty SVG path for every nav operation id', () => {
    for (const item of NAV_ITEMS) {
      const d = OP_ICONS[item.id] ?? '';
      expect(d.length).withContext(`missing icon for nav id ${item.id}`).toBeGreaterThan(0);
      expect(d).withContext(`nav id ${item.id} drifted from registry`).toBe(item.icon);
    }
  });
});
