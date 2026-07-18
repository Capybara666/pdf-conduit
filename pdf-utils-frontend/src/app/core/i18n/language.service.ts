import { Injectable, inject, signal } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';

import { LANG_STORAGE_KEY, LANGUAGE_CODES, LANGUAGES, Language } from './languages';

/**
 * Thin wrapper over `TranslocoService` for the header language picker. Owns the
 * active-language signal, persists the user's choice to localStorage and keeps
 * the document's `<html lang>` attribute in sync (accessibility / SEO).
 */
@Injectable({ providedIn: 'root' })
export class LanguageService {
  private readonly transloco = inject(TranslocoService);

  readonly languages: Language[] = LANGUAGES;
  readonly active = signal<string>(this.transloco.getActiveLang());

  constructor() {
    // Reflect the boot language on <html> and follow any later change.
    this.applyHtmlLang(this.transloco.getActiveLang());
    this.transloco.langChanges$.subscribe((lang) => {
      this.active.set(lang);
      this.applyHtmlLang(lang);
    });
  }

  /** Switch the live UI language, persisting the choice. */
  setLang(code: string): void {
    if (!LANGUAGE_CODES.includes(code)) return;
    this.transloco.setActiveLang(code);
    try {
      localStorage.setItem(LANG_STORAGE_KEY, code);
    } catch {
      // localStorage may be unavailable (private mode); ignore.
    }
  }

  private applyHtmlLang(lang: string): void {
    if (typeof document !== 'undefined') {
      document.documentElement.setAttribute('lang', lang);
    }
  }
}
