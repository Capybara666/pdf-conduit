import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Translation, TranslocoLoader } from '@jsverse/transloco';

/**
 * Loads a locale dictionary from the static `public/i18n/<lang>.json` assets.
 * These are same-origin static files (copied into the build output by the
 * `application` builder), so nothing hits an external CDN — CSP-safe. Only the
 * active locale is fetched, so the extra dictionaries stay lazy.
 */
@Injectable({ providedIn: 'root' })
export class TranslocoHttpLoader implements TranslocoLoader {
  private readonly http = inject(HttpClient);

  getTranslation(lang: string) {
    return this.http.get<Translation>(`/i18n/${lang}.json`);
  }
}
