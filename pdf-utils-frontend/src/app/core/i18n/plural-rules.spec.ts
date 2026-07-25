import MessageFormat from '@messageformat/core';

import { LANGUAGE_CODES } from './languages';

/**
 * Every locale's ICU plurals must compile under THAT locale's rules.
 *
 * <p>This exists because of a real, shipped regression: the messageformat
 * transpiler is constructed once with whatever `locales` it is given and only
 * calls `setLocale()` from `onLangChanged` — i.e. on a *change*. Booting
 * straight into a stored language fires no change, so the rules stayed English
 * and every Slavic `few`/`many` branch threw
 * "The plural case few is not valid in this locale" at render time, taking the
 * whole page down. It went unnoticed while the copy only used `one`/`other`,
 * which happen to be valid in English too.
 *
 * <p>The guard therefore checks both halves: each locale compiles under its own
 * rules, AND a locale that needs non-English categories genuinely fails under
 * English ones — otherwise this test would pass even if the wiring regressed.
 */
describe('ICU plural categories', () => {
  const dictionaries = new Map<string, Record<string, string>>();

  const flatten = (value: unknown, prefix = ''): Record<string, string> => {
    const out: Record<string, string> = {};
    for (const [key, entry] of Object.entries(value as Record<string, unknown>)) {
      if (entry && typeof entry === 'object') Object.assign(out, flatten(entry, `${prefix}${key}.`));
      else out[`${prefix}${key}`] = String(entry);
    }
    return out;
  };

  const plurals = (dict: Record<string, string>) =>
    Object.entries(dict).filter(([, value]) => /\{\s*\w+\s*,\s*plural/.test(value));

  beforeAll(async () => {
    await Promise.all(
      LANGUAGE_CODES.map(async (lang) => {
        const res = await fetch(`/i18n/${lang}.json`);
        dictionaries.set(lang, flatten(await res.json()));
      }),
    );
  });

  for (const lang of LANGUAGE_CODES) {
    it(`${lang}: every plural compiles under ${lang} rules`, () => {
      const mf = new MessageFormat(lang);
      const broken: string[] = [];
      for (const [key, value] of plurals(dictionaries.get(lang)!)) {
        try {
          mf.compile(value);
        } catch (e) {
          broken.push(`${key}: ${(e as Error).message}`);
        }
      }
      expect(broken).toEqual([]);
    });
  }

  it('a locale needing non-English categories really fails under English rules', () => {
    // Proves the per-locale compile above is load-bearing, not vacuous.
    const en = new MessageFormat('en');
    const slavic = ['pl', 'ru', 'uk'].filter((l) => LANGUAGE_CODES.includes(l as never));
    expect(slavic.length).toBeGreaterThan(0);

    for (const lang of slavic) {
      const withCategories = plurals(dictionaries.get(lang)!).filter(([, v]) =>
        /\b(few|many)\s*\{/.test(v),
      );
      expect(withCategories.length)
        .withContext(`${lang} should use few/many somewhere`)
        .toBeGreaterThan(0);

      const survives = withCategories.filter(([, value]) => {
        try {
          en.compile(value);
          return true;
        } catch {
          return false;
        }
      });
      expect(survives)
        .withContext(`${lang} plurals must NOT be compilable as English`)
        .toEqual([]);
    }
  });
});
