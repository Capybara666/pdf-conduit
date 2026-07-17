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
    icon: 'M4 4h9l3 3h4v13H4z M4 4v16',
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
    icon: 'M4 6h10 M4 12h16 M4 18h7 M18 4v6 M15 7h6',
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
    icon: 'M4 4h16v16H4z M8 8h8 M8 12h8 M8 16h5',
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

  // Edit
  {
    id: 'metadata',
    label: 'Metadata',
    description: 'View, edit or strip document info.',
    group: 'edit',
    icon: 'M12 8a2 2 0 1 0 0-.01 M4 6h16v12H4z M8 14h8 M8 17h5',
  },
  {
    id: 'watermark',
    label: 'Watermark',
    description: 'Stamp text or an image over every page.',
    group: 'edit',
    icon: 'M4 4h16v16H4z M7 15l3-6 3 6 M8.2 13h3.6 M15 9v6',
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
