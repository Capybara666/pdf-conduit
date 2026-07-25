import { EnvironmentProviders, ModuleWithProviders, Provider } from '@angular/core';
import { Translation, TranslocoTestingModule, TranslocoTestingOptions } from '@jsverse/transloco';
import { provideTranslocoMessageformat } from '@jsverse/transloco-messageformat';

import en from '../../../public/i18n/en.json';
import { DEFAULT_LANG, LANGUAGE_CODES } from '../core/i18n/languages';

/**
 * Transloco wiring for TestBed, mirroring the runtime setup in `app.config.ts`
 * minus the HTTP loader: the REAL `public/i18n/en.json` dictionary is handed to
 * Transloco's `TestingLoader` (synchronous, no HttpClient round-trip), and the
 * messageformat transpiler is provided so ICU strings (`{count, plural, …}`)
 * behave exactly as they do in the app.
 *
 * Using the real dictionary means a spec that asserts on rendered copy also
 * proves the key exists in `en.json` — a missing key surfaces as the raw key
 * in the DOM instead of silently passing against a stub.
 */
export function translocoTesting(
  options: TranslocoTestingOptions = {},
): ModuleWithProviders<TranslocoTestingModule> {
  return TranslocoTestingModule.forRoot({
    langs: { en: en as Translation },
    translocoConfig: {
      availableLangs: LANGUAGE_CODES,
      defaultLang: DEFAULT_LANG,
      fallbackLang: DEFAULT_LANG,
      reRenderOnLangChange: true,
      missingHandler: { useFallbackTranslation: true },
    },
    preloadLangs: true,
    ...options,
  });
}

/** Providers that must accompany {@link translocoTesting} in `providers`. */
export const TRANSLOCO_TESTING_PROVIDERS: (Provider | EnvironmentProviders)[] = [
  provideTranslocoMessageformat(),
];
