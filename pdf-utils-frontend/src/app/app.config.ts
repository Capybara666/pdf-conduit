import { ApplicationConfig, isDevMode, provideZoneChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import {
  TitleStrategy,
  provideRouter,
  withComponentInputBinding,
  withInMemoryScrolling,
} from '@angular/router';
import { provideTransloco } from '@jsverse/transloco';
import { provideTranslocoMessageformat } from '@jsverse/transloco-messageformat';

import { routes } from './app.routes';
import { TranslocoHttpLoader } from './core/i18n/transloco-loader';
import { DEFAULT_LANG, LANGUAGE_CODES, resolveInitialLang } from './core/i18n/languages';
import { TranslatedTitleStrategy } from './core/title-strategy';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(
      routes,
      withComponentInputBinding(),
      withInMemoryScrolling({ anchorScrolling: 'enabled', scrollPositionRestoration: 'enabled' }),
    ),
    // XHR backend (the default), NOT `withFetch()`: the fetch backend cannot
    // report UPLOAD progress at all — it only surfaces download events. Every
    // operation here posts multipart files, and a multi-minute upload with no
    // percentage reads as a hang, so the transport that can measure it wins.
    // There is no SSR build, which is the usual reason to prefer fetch.
    provideHttpClient(),
    { provide: TitleStrategy, useClass: TranslatedTitleStrategy },
    provideTransloco({
      config: {
        availableLangs: LANGUAGE_CODES,
        // Explicit stored choice → navigator match → English.
        defaultLang: resolveInitialLang(),
        fallbackLang: DEFAULT_LANG,
        // Re-render every binding live when the active language changes.
        reRenderOnLangChange: true,
        prodMode: !isDevMode(),
        missingHandler: { useFallbackTranslation: true },
      },
      loader: TranslocoHttpLoader,
    }),
    // The transpiler only calls setLocale() from onLangChanged, i.e. on a *change*.
    // Booting straight into a stored language fires no change, so without this the
    // rules stay English and any non-English plural category (Slavic `few`/`many`)
    // throws "The plural case few is not valid in this locale" at render time.
    provideTranslocoMessageformat({ locales: resolveInitialLang() }),
  ],
};
