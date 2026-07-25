/**
 * Static catalog of navigable views (operations + wizard + pipeline).
 *
 * Single source of truth for the sidebar and the router config. The backend
 * `GET /api/operations` catalog is the authority on which *operations* exist;
 * this list adds the UI-only entries (wizard, pipeline) and carries icons,
 * grouping and route paths. `id` matches the REST endpoint id where applicable.
 *
 * USER-VISIBLE TEXT LIVES IN `public/i18n/*.json`, NOT HERE. Every surface
 * resolves `op.<id>.label` / `op.<id>.description` through Transloco
 * (`sidebar.component.html`, `home.page.html`), and `locales.spec.ts` proves
 * those keys exist for every entry below. English copy in this file would be
 * rendered by nothing and would rot silently — it already had, which is why the
 * `label`/`description` fields are gone.
 */

export interface NavItem {
  /** Operation id / route path segment, e.g. "merge", "to-pdf". Also the i18n
   *  key stem: `op.<id>.label` / `op.<id>.description`. */
  id: string;
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

  crop: g(
    '<path d="M8.5 7 H15.5 V17 H8.5 Z" fill="currentColor" stroke="none" opacity=".18"/>' +
      '<path d="M5 9 V5 H9"/>' +
      '<path d="M15 5 H19 V9"/>' +
      '<path d="M5 15 V19 H9"/>' +
      '<path d="M15 19 H19 V15"/>',
  ),

  // --- Existing single-path glyphs, wrapped into the uniform format ---
  'to-pdf': g(
    '<path d="M11 4 H17 L20 7 V20 H11 Z"/>' +
      '<path d="M17 4 V7 H20"/>' +
      '<path d="M3 12 H10.5"/>' +
      '<path d="M8 9.5 L10.5 12 L8 14.5"/>',
  ),
  'to-images': g(
    '<path d="M4 6 H20 V18 H4 Z"/>' +
      '<circle cx="8.5" cy="10" r="1.4"/>' +
      '<path d="M5 17 L9 13 L12 16 L15 13 L19 17"/>',
  ),
  'to-text': g(
    '<path d="M5 3 H14 L17 6 V21 H5 Z"/>' +
      '<path d="M14 3 V6 H17"/>' +
      '<path d="M8 10 H14"/>' +
      '<path d="M8 13 H14"/>' +
      '<path d="M8 16 H12"/>',
  ),
  protect: g(
    '<path d="M8 10.5 V7 a4 4 0 0 1 8 0 V10.5"/>' +
      '<path d="M5.5 10.5 H18.5 V21 H5.5 Z"/>' +
      '<circle cx="12" cy="15.2" r="1.3"/>' +
      '<path d="M12 16.5 V18.6"/>',
  ),
  unlock: g(
    '<path d="M8 10.5 V6.5 a4 4 0 0 1 7.7 -1.5"/>' +
      '<path d="M5.5 10.5 H18.5 V21 H5.5 Z"/>' +
      '<circle cx="12" cy="15.2" r="1.3"/>' +
      '<path d="M12 16.5 V18.6"/>',
  ),
  redact: g(
    '<path d="M5 3 H14 L17 6 V21 H5 Z"/>' +
      '<path d="M14 3 V6 H17"/>' +
      '<path d="M8 10 H14"/>' +
      '<path d="M7.5 12.4 H14.5 V14.8 H7.5 Z" fill="currentColor"/>' +
      '<path d="M8 17.4 H11.5"/>',
  ),
  // GDPR scan (shield) + a redaction bar struck through it: scan for PII, then black it out.
  'gdpr-redact': g(
    '<path d="M12 3 L19 6 V11 C19 15.5 16 18.6 12 20.2 C8 18.6 5 15.5 5 11 V6 Z"/>' +
      '<path d="M8 10.4 H16"/>' +
      '<path d="M7.6 12.6 H16.4 V15 H7.6 Z" fill="currentColor"/>',
  ),
  sign: g(
    '<path d="M15 4.2 L19.8 9 L10.6 18.2 L5.8 18.4 L6 13.6 Z" fill="currentColor" stroke="none" opacity=".18"/>' +
      '<path d="M15 4.2 L19.8 9 L10.6 18.2 L5.8 18.4 L6 13.6 Z"/>' +
      '<path d="M12.8 6.4 L17.6 11.2"/>' +
      '<path d="M4 21 C6.5 21 6.5 19.2 8.5 19.2 C10 19.2 10 20.4 11.5 20.4 C13.2 20.4 14 19 16 19"/>',
  ),
  'gdpr-scan': g(
    '<path d="M12 3 L19 6 V11 C19 15.5 16 18.6 12 20.2 C8 18.6 5 15.5 5 11 V6 Z"/>' +
      '<path d="M8.8 11.6 L11 13.8 L15.2 9.2"/>',
  ),
  metadata: g(
    '<path d="M4 13 L11 6 H18 V13 L11 20 Z"/>' +
      '<circle cx="15" cy="9.5" r="1.1"/>',
  ),
  wizard: g(
    '<path d="M12 3.2 L14.05 9.1 L20.2 9.25 L15.25 12.95 L17.05 18.9 L12 15.35 L6.95 18.9 L8.75 12.95 L3.8 9.25 L9.95 9.1 Z"/>',
  ),
  pipeline: g(
    '<circle cx="4.5" cy="6" r="1.7" fill="currentColor" stroke="none"/>' +
      '<circle cx="4.5" cy="18" r="1.7" fill="currentColor" stroke="none"/>' +
      '<circle cx="19.5" cy="12" r="1.7" fill="currentColor" stroke="none"/>' +
      '<path d="M6.2 6 C11.5 6 12 12 17.8 12"/>' +
      '<path d="M6.2 18 C11.5 18 12 12 17.8 12"/>',
  ),

  nup: g(
    '<path d="M3 5.5 H12 V18.5 H3 Z" fill="currentColor" stroke="none" opacity=".18"/>' +
      '<path d="M3 5.5 H21 V18.5 H3 Z"/>' +
      '<path d="M12 5.5 V18.5"/>',
  ),

  'page-marks': g(
    '<path d="M5 3 H14 L17 6 V21 H5 Z"/>' +
      '<path d="M14 3 V6 H17"/>' +
      '<path d="M7 8 H15 V10.2 H7 Z" fill="currentColor" stroke="none" opacity=".18"/>' +
      '<path d="M7 8 H15 V10.2 H7 Z"/>' +
      '<path d="M7 15.8 H15 V18 H7 Z" fill="currentColor" stroke="none" opacity=".18"/>' +
      '<path d="M7 15.8 H15 V18 H7 Z"/>',
  ),

  ocr: g(
    '<path d="M5 3 H14 L17 6 V21 H5 Z"/>' +
      '<path d="M14 3 V6 H17"/>' +
      '<path d="M7.5 9 H14.5 V18 H7.5 Z" fill="currentColor" stroke="none" opacity=".18"/>' +
      '<path d="M8.8 16 L11 10.5 L13.2 16"/>' +
      '<path d="M9.6 14.2 H12.4"/>',
  ),

  // Repair — an open-end wrench: a duotone head disc, the jaw drawn as a wide
  // open "C" facing up-right, and a diagonal handle. No page outline: at 24px a
  // wrench *inside* a document turns to mush, and the tool alone is the clearer
  // "fix this" signal.
  // Sized to the same optical extent as its neighbours: the ink spans ~4-20 on
  // both axes, like compress (3.5-20.5). The first draft only reached 4.8-19.2
  // *diagonally*, so it read noticeably smaller than every other glyph.
  repair: g(
    '<circle cx="16.4" cy="7.6" r="3.7" fill="currentColor" stroke="none" opacity=".18"/>' +
      '<path d="M20.04 8.24 A3.7 3.7 0 1 1 15.76 3.96"/>' +
      '<path d="M13.78 10.22 L4.4 19.6"/>',
  ),

  // Pipeline SOURCE node (not an operation) — a plain page glyph.
  source: g('<path d="M6 2h9l5 5v15H6z M15 2v5h5"/>'),
};

export const NAV_ITEMS: NavItem[] = [
  // Organise
  {
    id: 'merge',
    group: 'organise',
    icon: OP_ICONS['merge'],
  },
  {
    id: 'extract',
    group: 'organise',
    icon: OP_ICONS['extract'],
  },
  {
    id: 'rotate',
    group: 'organise',
    icon: OP_ICONS['rotate'],
  },
  {
    id: 'arrange',
    group: 'organise',
    icon: OP_ICONS['arrange'],
  },
  {
    id: 'nup',
    group: 'organise',
    icon: OP_ICONS['nup'],
  },

  // Optimise
  {
    id: 'compress',
    group: 'optimise',
    icon: OP_ICONS['compress'],
  },

  {
    id: 'repair',
    group: 'optimise',
    icon: OP_ICONS['repair'],
  },

  // Convert
  {
    id: 'to-pdf',
    group: 'convert',
    icon: OP_ICONS['to-pdf'],
  },
  {
    id: 'to-images',
    group: 'convert',
    icon: OP_ICONS['to-images'],
  },
  {
    id: 'to-text',
    group: 'convert',
    icon: OP_ICONS['to-text'],
  },
  {
    id: 'ocr',
    group: 'convert',
    icon: OP_ICONS['ocr'],
  },

  // Secure
  {
    id: 'protect',
    group: 'secure',
    icon: OP_ICONS['protect'],
  },
  {
    id: 'unlock',
    group: 'secure',
    icon: OP_ICONS['unlock'],
  },
  {
    id: 'redact',
    group: 'secure',
    icon: OP_ICONS['redact'],
  },
  {
    id: 'sign',
    group: 'secure',
    icon: OP_ICONS['sign'],
  },
  {
    id: 'gdpr-scan',
    group: 'advanced',
    icon: OP_ICONS['gdpr-scan'],
  },

  // Edit
  {
    id: 'metadata',
    group: 'edit',
    icon: OP_ICONS['metadata'],
  },
  {
    id: 'watermark',
    group: 'edit',
    icon: OP_ICONS['watermark'],
  },
  {
    id: 'crop',
    group: 'edit',
    icon: OP_ICONS['crop'],
  },
  {
    id: 'page-marks',
    group: 'edit',
    icon: OP_ICONS['page-marks'],
  },

  // Advanced
  {
    id: 'wizard',
    group: 'advanced',
    icon: OP_ICONS['wizard'],
  },
  {
    id: 'pipeline',
    group: 'advanced',
    icon: OP_ICONS['pipeline'],
  },
];

export interface NavGroup {
  /** Grouping id and i18n key stem — the sidebar renders `nav.groups.<key>`. */
  key: NavItem['group'];
}

/** Sidebar section order. Headings come from `nav.groups.*`, not from here. */
export const NAV_GROUPS: NavGroup[] = [
  { key: 'organise' },
  { key: 'optimise' },
  { key: 'convert' },
  { key: 'secure' },
  { key: 'edit' },
  { key: 'advanced' },
];
