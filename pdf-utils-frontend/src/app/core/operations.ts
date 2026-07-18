/**
 * Static catalog of navigable views (operations + wizard + pipeline).
 *
 * Single source of truth for the sidebar and the router config. The backend
 * `GET /api/operations` catalog is the authority on which *operations* exist;
 * this list adds the UI-only entries (wizard, pipeline) and carries labels,
 * icons and route paths. `id` matches the REST endpoint id where applicable.
 */

export interface NavItem {
  /** Operation id / route path segment, e.g. "merge", "to-pdf". */
  id: string;
  /** Human label shown in the sidebar. */
  label: string;
  /** Short blurb for the placeholder / tooltip. */
  description: string;
  /**
   * Inner SVG markup for a 24×24 glyph — a `<g>` group with `<path>`/`<circle>`
   * children (see {@link OP_ICONS}), rendered via `<app-op-icon [markup]>`.
   * NOT a single path `d`; multi-element icons need real SVG nodes.
   */
  icon: string;
  /** Sidebar grouping. */
  group: 'organise' | 'optimise' | 'convert' | 'secure' | 'edit' | 'advanced';
}

/**
 * Wrap plain stroke geometry in the shared glyph group so the whole registry is
 * one uniform format (inner SVG markup). Duotone children carry their own
 * `fill="currentColor" stroke="none" opacity=".18"` and override these defaults.
 */
const g = (inner: string): string =>
  `<g fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">${inner}</g>`;

/**
 * Operation glyph registry — the single source of truth for icon artwork.
 *
 * Each value is the *inner* markup of a 24×24 SVG (the `<g>` group and its
 * paths/circles), so a glyph can be multi-element with an optional duotone
 * accent shape. Rendered by `<app-op-icon [name]>` / `[markup]` (which injects
 * it into a real `<svg>`, the only reliable way to create SVG-namespaced nodes).
 * Keyed by operation id, plus `source` for the pipeline SOURCE node.
 */
export const OP_ICONS: Record<string, string> = {
  // --- Approved batch-1 rich glyphs (multi-element, duotone accents) ---
  rotate: g(
    '<path d="M12 12 L12 5 A7 7 0 0 1 19 12 Z" fill="currentColor" stroke="none" opacity=".18"/>' +
      '<circle cx="12" cy="12" r="0.9" fill="currentColor" stroke="none"/>' +
      '<path d="M19 12 A7 7 0 1 1 15.5 5.9"/>' +
      '<path d="M14.3 3.6 L15.5 5.9 L12.9 6"/>',
  ),
  compress: g(
    '<path d="M5 12 H19"/>' +
      '<path d="M12 3.5 V8.5"/>' +
      '<path d="M9.6 6.1 L12 8.5 L14.4 6.1"/>' +
      '<path d="M12 20.5 V15.5"/>' +
      '<path d="M9.6 17.9 L12 15.5 L14.4 17.9"/>',
  ),
  merge: g(
    '<path d="M3 3 H8 V9 H3 Z" fill="currentColor" stroke="none" opacity=".18"/>' +
      '<path d="M3 15 H8 V21 H3 Z" fill="currentColor" stroke="none" opacity=".18"/>' +
      '<path d="M3 3 H8 V9 H3 Z"/>' +
      '<path d="M3 15 H8 V21 H3 Z"/>' +
      '<path d="M8 6 C12 6 11 12 15 12"/>' +
      '<path d="M8 18 C12 18 11 12 15 12"/>' +
      '<path d="M15 8 H21 V16 H15 Z"/>',
  ),
  extract: g(
    '<path d="M7.7 8.6 L19 16"/>' +
      '<path d="M7.7 15.4 L19 8"/>' +
      '<circle cx="6" cy="7.5" r="2"/>' +
      '<circle cx="6" cy="16.5" r="2"/>',
  ),
  arrange: g(
    '<path d="M6 5 V19"/>' +
      '<path d="M3.6 7.4 L6 5 L8.4 7.4"/>' +
      '<path d="M3.6 16.6 L6 19 L8.4 16.6"/>' +
      '<path d="M11 7 H20"/>' +
      '<path d="M11 12 H20"/>' +
      '<path d="M11 17 H17"/>',
  ),
  watermark: g(
    '<path d="M5 3 H14 L17 6 V21 H5 Z"/>' +
      '<path d="M14 3 V6 H17"/>' +
      '<path d="M11 8 C9 10.5 8 12.4 8 14 A3 3 0 0 0 14 14 C14 12.4 13 10.5 11 8 Z" fill="currentColor" stroke="none" opacity=".18"/>' +
      '<path d="M11 8 C9 10.5 8 12.4 8 14 A3 3 0 0 0 14 14 C14 12.4 13 10.5 11 8 Z"/>' +
      '<path d="M9.7 13.6 A1.6 1.6 0 0 0 11.3 15.2"/>',
  ),

  // --- Existing single-path glyphs, wrapped into the uniform format ---
  'to-pdf': g('<path d="M4 4h16v16H4z M8 8h8 M8 12h8 M8 16h5"/>'),
  'to-images': g('<path d="M3 5h18v14H3z M7 10a1.5 1.5 0 1 0 0-.01 M6 17l4-4 3 3 3-3 4 4"/>'),
  'to-text': g('<path d="M6 3h12v18H6z M9 8h6 M9 12h6 M9 16h4"/>'),
  protect: g('<path d="M6 10V8a6 6 0 0 1 12 0v2 M5 10h14v10H5z M12 14v3"/>'),
  unlock: g('<path d="M6 10V8a6 6 0 0 1 11-3 M5 10h14v10H5z M12 14v3"/>'),
  redact: g('<path d="M4 5h16v14H4z M7 9h6v4H7z M15 10h3 M15 14h2"/>'),
  'gdpr-scan': g('<path d="M12 3l7 3v5c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6z M9 11.5l2 2 4-4.5"/>'),
  metadata: g('<path d="M12 8a2 2 0 1 0 0-.01 M4 6h16v12H4z M8 14h8 M8 17h5"/>'),
  wizard: g(
    '<path d="M5 3l1.5 3L10 7 6.5 8 5 11 3.5 8 0 7 3.5 6z M17 9l1 2 2 1-2 1-1 2-1-2-2-1 2-1z M14 15h6v6h-6z"/>',
  ),
  pipeline: g('<path d="M4 6a2 2 0 1 0 0-.01 M20 18a2 2 0 1 0 0-.01 M6 6h6a4 4 0 0 1 4 4v4 M14 14h4"/>'),

  // Pipeline SOURCE node (not an operation) — a plain page glyph.
  source: g('<path d="M6 2h9l5 5v15H6z M15 2v5h5"/>'),
};

export const NAV_ITEMS: NavItem[] = [
  // Organise
  {
    id: 'merge',
    label: 'Merge',
    description: 'Combine several PDFs (or images/office docs) into one.',
    group: 'organise',
    icon: OP_ICONS['merge'],
  },
  {
    id: 'extract',
    label: 'Extract',
    description: 'Pull selected pages out of a PDF.',
    group: 'organise',
    icon: OP_ICONS['extract'],
  },
  {
    id: 'rotate',
    label: 'Rotate',
    description: 'Rotate pages 90°, 180° or 270°.',
    group: 'organise',
    icon: OP_ICONS['rotate'],
  },
  {
    id: 'arrange',
    label: 'Arrange',
    description: 'Reorder, reverse or duplicate pages.',
    group: 'organise',
    icon: OP_ICONS['arrange'],
  },

  // Optimise
  {
    id: 'compress',
    label: 'Compress',
    description: 'Shrink a PDF toward a target size.',
    group: 'optimise',
    icon: OP_ICONS['compress'],
  },

  // Convert
  {
    id: 'to-pdf',
    label: 'To PDF',
    description: 'Convert images and office documents to PDF.',
    group: 'convert',
    icon: OP_ICONS['to-pdf'],
  },
  {
    id: 'to-images',
    label: 'To Images',
    description: 'Render PDF pages to PNG or JPG.',
    group: 'convert',
    icon: OP_ICONS['to-images'],
  },
  {
    id: 'to-text',
    label: 'To Text',
    description: 'Extract the text content of a PDF.',
    group: 'convert',
    icon: OP_ICONS['to-text'],
  },

  // Secure
  {
    id: 'protect',
    label: 'Protect',
    description: 'Add password encryption to a PDF.',
    group: 'secure',
    icon: OP_ICONS['protect'],
  },
  {
    id: 'unlock',
    label: 'Unlock',
    description: 'Remove a known password from a PDF.',
    group: 'secure',
    icon: OP_ICONS['unlock'],
  },
  {
    id: 'redact',
    label: 'Redact',
    description: 'Permanently black out regions of a page.',
    group: 'secure',
    icon: OP_ICONS['redact'],
  },
  {
    id: 'gdpr-scan',
    label: 'GDPR Scan',
    description: 'Scan a PDF for personal data (GDPR / PII).',
    group: 'advanced',
    icon: OP_ICONS['gdpr-scan'],
  },

  // Edit
  {
    id: 'metadata',
    label: 'Metadata',
    description: 'View, edit or strip document info.',
    group: 'edit',
    icon: OP_ICONS['metadata'],
  },
  {
    id: 'watermark',
    label: 'Watermark',
    description: 'Stamp text or an image over every page.',
    group: 'edit',
    icon: OP_ICONS['watermark'],
  },

  // Advanced
  {
    id: 'wizard',
    label: 'Wizard',
    description: 'Guided step-by-step build & export.',
    group: 'advanced',
    icon: OP_ICONS['wizard'],
  },
  {
    id: 'pipeline',
    label: 'Pipeline',
    description: 'Chain operations as a node graph.',
    group: 'advanced',
    icon: OP_ICONS['pipeline'],
  },
];

export interface NavGroup {
  key: NavItem['group'];
  label: string;
}

export const NAV_GROUPS: NavGroup[] = [
  { key: 'organise', label: 'Organise' },
  { key: 'optimise', label: 'Optimise' },
  { key: 'convert', label: 'Convert' },
  { key: 'secure', label: 'Secure' },
  { key: 'edit', label: 'Edit' },
  { key: 'advanced', label: 'Advanced' },
];
