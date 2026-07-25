import { ComponentFixture, TestBed } from '@angular/core/testing';

import { NAV_ITEMS, OP_ICONS } from './operations';
import { KIND_TO_OP } from './pipeline.models';
import { OpIconComponent } from '../shared/op-icon/op-icon.component';

/**
 * NodeKind op ids the pipeline resolves through OP_ICONS. Derived from the real
 * `KIND_TO_OP` (not a hand-copied list) so a newly-added NodeKind cannot land a
 * glyph-less palette chip / node card without failing here.
 */
const NODE_KIND_OP_IDS = Object.values(KIND_TO_OP);

const EMOJI = /\p{Extended_Pictographic}/u;

/** A glyph must be non-empty inner SVG markup with at least one drawable node. */
function assertValidGlyph(markup: string): void {
  expect(markup).withContext('non-empty').toBeTruthy();
  expect(markup.length).toBeGreaterThan(0);
  expect(/<path\b|<circle\b/.test(markup)).withContext('has <path> or <circle>').toBe(true);
  expect(EMOJI.test(markup)).withContext('no emoji').toBe(false);
}

describe('OP_ICONS registry', () => {
  it('resolves every operation id (NAV_ITEMS) to valid multi-element markup', () => {
    for (const item of NAV_ITEMS) {
      expect(OP_ICONS[item.id]).withContext(`OP_ICONS[${item.id}]`).toBeDefined();
      assertValidGlyph(item.icon);
      assertValidGlyph(OP_ICONS[item.id]);
      // NavItem.icon is the resolved registry markup, not a bare path `d`.
      expect(item.icon).toBe(OP_ICONS[item.id]);
      expect(item.icon.startsWith('M')).toBe(false);
    }
  });

  it('resolves every pipeline NodeKind (incl. SOURCE) to a valid glyph', () => {
    for (const id of NODE_KIND_OP_IDS) {
      expect(OP_ICONS[id]).withContext(`OP_ICONS[${id}]`).toBeDefined();
      assertValidGlyph(OP_ICONS[id]);
    }
  });

  it('keeps the whole registry uniform and emoji-free', () => {
    const values = Object.values(OP_ICONS);
    expect(values.length).toBeGreaterThan(0);
    for (const v of values) assertValidGlyph(v);
  });
});

describe('OpIconComponent rendering', () => {
  let fixture: ComponentFixture<OpIconComponent>;
  let component: OpIconComponent;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [OpIconComponent] });
    fixture = TestBed.createComponent(OpIconComponent);
    component = fixture.componentInstance;
  });

  it('materialises multi-element SVG children in the SVG namespace', () => {
    component.markup = OP_ICONS['merge'];
    fixture.detectChanges();

    const svg = fixture.nativeElement.querySelector('svg') as SVGSVGElement;
    expect(svg).toBeTruthy();

    // Real SVG-namespaced path nodes must be created (proves innerHTML-on-svg works).
    const paths = svg.querySelectorAll('path');
    expect(paths.length).toBeGreaterThan(1);
    expect(paths[0].namespaceURI).toBe('http://www.w3.org/2000/svg');
    expect(paths[0] instanceof SVGPathElement).toBe(true);
  });

  it('renders the .18 duotone accent shape', () => {
    component.markup = OP_ICONS['rotate'];
    fixture.detectChanges();

    const svg = fixture.nativeElement.querySelector('svg') as SVGSVGElement;
    const duotone = Array.from(svg.querySelectorAll('*')).filter(
      (el) => el.getAttribute('opacity') === '.18' && el.getAttribute('fill') === 'currentColor',
    );
    expect(duotone.length).toBeGreaterThan(0);
    // rotate also carries a filled centre dot circle.
    expect(svg.querySelector('circle')).toBeTruthy();
  });

  it('resolves glyphs by name and clears when unknown', () => {
    component.name = 'compress';
    fixture.detectChanges();
    let svg = fixture.nativeElement.querySelector('svg') as SVGSVGElement;
    expect(svg.querySelectorAll('path').length).toBeGreaterThan(1);

    component.name = 'definitely-not-an-op';
    fixture.detectChanges();
    svg = fixture.nativeElement.querySelector('svg') as SVGSVGElement;
    expect(svg.querySelectorAll('path').length).toBe(0);
  });
});
