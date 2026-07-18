import { Component, Input } from '@angular/core';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';

import { OP_ICONS } from '../../core/operations';

/**
 * Renders a rich, multi-element operation glyph inside a 24×24 SVG.
 *
 * Icons in {@link OP_ICONS} are *inner* SVG markup — a `<g>` group with one or
 * more `<path>`/`<circle>` children (plus optional duotone fills). A single
 * `<path [attr.d]>` can't represent them, and Angular's `[innerHTML]` on a plain
 * element does NOT create SVG-namespaced nodes. The trick: bind `[innerHTML]` on
 * a *real* `<svg>` host — the browser's fragment parser then materialises the
 * children in the SVG namespace, so multi-element glyphs (and the .18 duotone
 * shapes) render correctly. The markup is our own trusted constant, so we
 * bypass the sanitizer (which would otherwise strip SVG structure).
 *
 * `currentColor` inheritance and width/height sizing flow from the host element,
 * so callers size it via a class on `<app-op-icon>` (or `.parent app-op-icon`).
 */
@Component({
  selector: 'app-op-icon',
  standalone: true,
  template: `<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false" [innerHTML]="safe"></svg>`,
  styles: [
    `
      :host {
        display: inline-flex;
        line-height: 0;
      }
      svg {
        width: 100%;
        height: 100%;
        display: block;
      }
    `,
  ],
})
export class OpIconComponent {
  protected safe: SafeHtml = '';

  constructor(private readonly sanitizer: DomSanitizer) {}

  /** Look the glyph up by operation id / NodeKind key (e.g. "merge", "source"). */
  @Input() set name(value: string | null | undefined) {
    this.render((value && OP_ICONS[value]) || '');
  }

  /** Inject already-resolved inner markup (e.g. `NavItem.icon`). */
  @Input() set markup(value: string | null | undefined) {
    this.render(value ?? '');
  }

  private render(inner: string): void {
    // Trusted, in-repo constants — safe to bypass HTML sanitization so the SVG
    // children survive intact.
    this.safe = this.sanitizer.bypassSecurityTrustHtml(inner);
  }
}
