import de from '../../../../public/i18n/de.json';
import en from '../../../../public/i18n/en.json';
import es from '../../../../public/i18n/es.json';
import fr from '../../../../public/i18n/fr.json';
// Aliased: a bare `it` would shadow Jasmine's `it()` for the whole file.
import itLocale from '../../../../public/i18n/it.json';
import ja from '../../../../public/i18n/ja.json';
import ko from '../../../../public/i18n/ko.json';
import nl from '../../../../public/i18n/nl.json';
import pl from '../../../../public/i18n/pl.json';
import pt from '../../../../public/i18n/pt.json';
import ru from '../../../../public/i18n/ru.json';
import tr from '../../../../public/i18n/tr.json';
import uk from '../../../../public/i18n/uk.json';
import zh from '../../../../public/i18n/zh.json';

import { TestBed } from '@angular/core/testing';
import { Translation, TranslocoService } from '@jsverse/transloco';

import { LANGUAGE_CODES } from './languages';
import { TRANSLOCO_TESTING_PROVIDERS, translocoTesting } from '../../testing/transloco-testing';

/**
 * Web-side counterpart to the desktop module's `MessagesParityTest`.
 *
 * `public/i18n/*.json` is the file set that changes most often and had the
 * weakest guard (a python snippet someone had to remember to run). A key that
 * exists only in `en.json` renders as the raw dotted key in a translated UI;
 * a key whose placeholders were dropped or renamed during translation is worse
 * — Transloco/messageformat interpolates against the parameters the *caller*
 * passes, so the value silently loses its number/name (or, for ICU, fails to
 * pluralise) only in that one language.
 *
 * The locales are imported statically rather than globbed so that adding a
 * `public/i18n/xx.json` without registering it here (or in `languages.ts`)
 * fails the `LANGUAGE_CODES` cross-check below.
 */

type Dict = Record<string, unknown>;

const LOCALES: ReadonlyArray<readonly [string, Dict]> = [
  ['de', de],
  ['en', en],
  ['es', es],
  ['fr', fr],
  ['it', itLocale],
  ['ja', ja],
  ['ko', ko],
  ['nl', nl],
  ['pl', pl],
  ['pt', pt],
  ['ru', ru],
  ['tr', tr],
  ['uk', uk],
  ['zh', zh],
];

const BASE_LANG = 'en';

/** Flatten a nested dictionary to `a.b.c` → leaf value. */
function flatten(dict: Dict, prefix = ''): Map<string, unknown> {
  const out = new Map<string, unknown>();
  for (const [key, value] of Object.entries(dict)) {
    const path = prefix + key;
    if (value && typeof value === 'object' && !Array.isArray(value)) {
      for (const [k, v] of flatten(value as Dict, path + '.')) {
        out.set(k, v);
      }
    } else {
      out.set(path, value);
    }
  }
  return out;
}

/**
 * The set of runtime placeholders a translation value depends on, covering both
 * dialects in use here:
 *
 * - Transloco interpolation — `{{name}}` (e.g. `up to {{size}} MB`).
 * - ICU / messageformat arguments — the identifier before `,` or `}`
 *   (e.g. `count` and `pages` in
 *   `{count, plural, one {# finding across {pages} pages} …}`).
 */
function placeholders(value: unknown): Set<string> {
  if (typeof value !== 'string') {
    return new Set();
  }
  const found = new Set<string>();
  for (const m of value.matchAll(/\{\{\s*([^}]+?)\s*\}\}/g)) {
    found.add(m[1]);
  }
  const icuOnly = value.replace(/\{\{[^}]*\}\}/g, '');
  for (const m of icuOnly.matchAll(/\{\s*([A-Za-z_][A-Za-z0-9_]*)\s*(?=[,}])/g)) {
    found.add(m[1]);
  }
  return found;
}

function sorted(set: Iterable<string>): string[] {
  return [...set].sort();
}

describe('i18n locale dictionaries', () => {
  const flat = new Map(LOCALES.map(([code, dict]) => [code, flatten(dict)] as const));
  const base = flat.get(BASE_LANG)!;

  it('covers exactly the languages offered by the switcher', () => {
    expect(sorted(flat.keys())).toEqual(sorted(LANGUAGE_CODES));
  });

  it('has a non-trivial base dictionary (guards a broken import)', () => {
    expect(base.size).toBeGreaterThan(500);
  });

  for (const [code] of LOCALES.filter(([c]) => c !== BASE_LANG)) {
    describe(`${code}.json`, () => {
      const dict = flat.get(code)!;

      it('has exactly the same key set as en.json', () => {
        const missing = sorted([...base.keys()].filter((k) => !dict.has(k)));
        const extra = sorted([...dict.keys()].filter((k) => !base.has(k)));
        expect(missing).withContext(`keys missing from ${code}.json`).toEqual([]);
        expect(extra).withContext(`keys in ${code}.json but not en.json`).toEqual([]);
      });

      it('uses the same placeholders as en.json for every shared key', () => {
        const mismatches: string[] = [];
        for (const [key, enValue] of base) {
          if (!dict.has(key)) continue;
          const expected = sorted(placeholders(enValue));
          const actual = sorted(placeholders(dict.get(key)));
          if (expected.join('|') !== actual.join('|')) {
            mismatches.push(`${key}: en={${expected}} ${code}={${actual}}`);
          }
        }
        expect(mismatches).withContext(`placeholder drift in ${code}.json`).toEqual([]);
      });

      it('has a non-empty string for every key', () => {
        const bad = sorted(
          [...dict.entries()]
            .filter(([, v]) => typeof v !== 'string' || v.trim() === '')
            .map(([k]) => k),
        );
        expect(bad).withContext(`empty or non-string values in ${code}.json`).toEqual([]);
      });
    });
  }

  it('has a non-empty string for every key in en.json', () => {
    const bad = sorted(
      [...base.entries()]
        .filter(([, v]) => typeof v !== 'string' || v.trim() === '')
        .map(([k]) => k),
    );
    expect(bad).toEqual([]);
  });

  /**
   * The page-marks tokens (`{page}`, `{n}`, `{pages}`, `{date}`) are literal text
   * the user has to TYPE into a header/footer slot — they are not translation
   * parameters. Written bare they are valid ICU argument syntax, so the
   * messageformat transpiler substitutes them with the (absent) parameter and
   * the copy renders "e.g. undefined / undefined". They must stay ICU-escaped.
   */
  describe('literal page-mark tokens', () => {
    const TOKEN_KEYS = [
      'pages.pageMarks.tokensHelp',
      'pages.pageMarks.slotPlaceholder',
      'pages.pageMarks.needSlot',
    ];
    /**
     * A `{token}` not wrapped in ICU escape quotes. Only meaningful for the
     * keys below — elsewhere `{pages}` / `{count}` really are ICU arguments.
     */
    const UNESCAPED = /(?<!')\{(?:page|pages|n|date)\}(?!')/;

    it('are escaped in every locale file', () => {
      const offenders: string[] = [];
      for (const [code, dict] of flat) {
        for (const key of TOKEN_KEYS) {
          const value = dict.get(key);
          if (typeof value === 'string' && UNESCAPED.test(value)) {
            offenders.push(`${code}.json → ${key}`);
          }
        }
      }
      expect(sorted(offenders)).toEqual([]);
    });

    it('render as literal braces (not "undefined") in every locale', () => {
      TestBed.configureTestingModule({
        imports: [
          translocoTesting({
            langs: Object.fromEntries(LOCALES.map(([code, dict]) => [code, dict as Translation])),
          }),
        ],
        providers: [TRANSLOCO_TESTING_PROVIDERS],
      });
      const transloco = TestBed.inject(TranslocoService);

      for (const [code] of LOCALES) {
        transloco.setActiveLang(code);
        for (const key of TOKEN_KEYS) {
          const rendered = String(transloco.translate(key));
          expect(rendered).withContext(`${code} → ${key}`).not.toContain('undefined');
          expect(rendered).withContext(`${code} → ${key}`).toContain('{page}');
          // The ICU escape quotes must be consumed, not shown to the user.
          expect(rendered).withContext(`${code} → ${key}`).not.toContain("'{page}'");
        }
      }
    });
  });
});
