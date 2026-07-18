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
  /** Inline SVG path data (24×24 viewBox). */
  icon: string;
  /** Sidebar grouping. */
  group: 'organise' | 'optimise' | 'convert' | 'secure' | 'edit' | 'advanced';
}

export const NAV_ITEMS: NavItem[] = [
  // Organise
  {
    id: 'merge',
    label: 'Merge',
    description: 'Combine several PDFs (or images/office docs) into one.',
    group: 'organise',
    // two inputs converging into one output (conduit) — matches desktop Icons.MERGE
    icon: 'M5 7 H10 M5 17 H10 M10 7 C14 7 14 12 19 12 M10 17 C14 17 14 12 19 12',
  },
  {
    id: 'extract',
    label: 'Extract',
    description: 'Pull selected pages out of a PDF.',
    group: 'organise',
    icon: 'M6 2h9l5 5v15H6z M15 2v5h5 M9 13h6 M9 17h6',
  },
  {
    id: 'rotate',
    label: 'Rotate',
    description: 'Rotate pages 90°, 180° or 270°.',
    group: 'organise',
    icon: 'M12 5a7 7 0 1 0 7 7 M12 5V2 L8 6l4 3z',
  },
  {
    id: 'arrange',
    label: 'Arrange',
    description: 'Reorder, reverse or duplicate pages.',
    group: 'organise',
    // up/down arrow beside stacked rows (reorder) — matches desktop Icons.ARRANGE
    icon: 'M6 5 V19 M3.5 8 L6 5 L8.5 8 M3.5 16 L6 19 L8.5 16 M11 7 H20 M11 12 H20 M11 17 H17',
  },

  // Optimise
  {
    id: 'compress',
    label: 'Compress',
    description: 'Shrink a PDF toward a target size.',
    group: 'optimise',
    icon: 'M12 3v6 M12 21v-6 M8 7l4-4 4 4 M8 17l4 4 4-4 M4 12h16',
  },

  // Convert
  {
    id: 'to-pdf',
    label: 'To PDF',
    description: 'Convert images and office documents to PDF.',
    group: 'convert',
    // a horizontal arrow feeding into a PDF page — matches desktop Icons.TO_PDF
    icon: 'M11 4 H17 L20 7 V20 H11 Z M17 4 V7 H20 M3 12 H9 M7 10 L9 12 L7 14',
  },
  {
    id: 'to-images',
    label: 'To Images',
    description: 'Render PDF pages to PNG or JPG.',
    group: 'convert',
    icon: 'M3 5h18v14H3z M7 10a1.5 1.5 0 1 0 0-.01 M6 17l4-4 3 3 3-3 4 4',
  },
  {
    id: 'to-text',
    label: 'To Text',
    description: 'Extract the text content of a PDF.',
    group: 'convert',
    icon: 'M6 3h12v18H6z M9 8h6 M9 12h6 M9 16h4',
  },

  // Secure
  {
    id: 'protect',
    label: 'Protect',
    description: 'Add password encryption to a PDF.',
    group: 'secure',
    icon: 'M6 10V8a6 6 0 0 1 12 0v2 M5 10h14v10H5z M12 14v3',
  },
  {
    id: 'unlock',
    label: 'Unlock',
    description: 'Remove a known password from a PDF.',
    group: 'secure',
    icon: 'M6 10V8a6 6 0 0 1 11-3 M5 10h14v10H5z M12 14v3',
  },
  {
    id: 'redact',
    label: 'Redact',
    description: 'Permanently black out regions of a page.',
    group: 'secure',
    icon: 'M4 5h16v14H4z M7 9h6v4H7z M15 10h3 M15 14h2',
  },
  {
    id: 'gdpr-scan',
    label: 'GDPR Scan',
    description: 'Scan a PDF for personal data (GDPR / PII).',
    group: 'advanced',
    icon: 'M12 3l7 3v5c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6z M9 11.5l2 2 4-4.5',
  },

  // Edit
  {
    id: 'metadata',
    label: 'Metadata',
    description: 'View, edit or strip document info.',
    group: 'edit',
    // a tag/label with a hole (metadata) — matches desktop Icons.METADATA
    icon: 'M4 13 L11 6 H18 V13 L11 20 Z M15 9.5 a1.1 1.1 0 1 0 0.02 0',
  },
  {
    id: 'watermark',
    label: 'Watermark',
    description: 'Stamp text or an image over every page.',
    group: 'edit',
    // a water droplet (watermark) — matches desktop Icons.WATERMARK
    icon: 'M12 4 C9 8 6.5 12 6.5 15 a5.5 5.5 0 0 0 11 0 C17.5 12 15 8 12 4 Z',
  },

  // Advanced
  {
    id: 'wizard',
    label: 'Wizard',
    description: 'Guided step-by-step build & export.',
    group: 'advanced',
    icon: 'M5 3l1.5 3L10 7 6.5 8 5 11 3.5 8 0 7 3.5 6z M17 9l1 2 2 1-2 1-1 2-1-2-2-1 2-1z M14 15h6v6h-6z',
  },
  {
    id: 'pipeline',
    label: 'Pipeline',
    description: 'Chain operations as a node graph.',
    group: 'advanced',
    icon: 'M4 6a2 2 0 1 0 0-.01 M20 18a2 2 0 1 0 0-.01 M6 6h6a4 4 0 0 1 4 4v4 M14 14h4',
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

/**
 * Canonical line-art icon registry, keyed by operation id — the SINGLE source of
 * truth for every operation glyph across the SPA (sidebar, landing tiles,
 * pipeline nodes, pipeline palette). Every entry is an inline SVG path `d`
 * authored in a 24×24 viewBox, mirroring the desktop `Icons.java` metaphors, and
 * meant to be stroked (fill=none, stroke=currentColor, round caps/joins) so it
 * inherits the active theme colour.
 *
 * Operation glyphs are derived directly from {@link NAV_ITEMS} so the nav can
 * never drift from the nodes again; `source` is the one pipeline-only glyph with
 * no nav entry (the desktop `Icons.SOURCE` folder).
 */
export const OP_ICONS: Record<string, string> = {
  // a folder (pipeline SOURCE node) — matches desktop Icons.SOURCE
  source: 'M4 7 H9.5 L11.5 9 H20 V19 H4 Z',
  ...Object.fromEntries(NAV_ITEMS.map((item) => [item.id, item.icon] as const)),
};
