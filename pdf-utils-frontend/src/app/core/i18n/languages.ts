/**
 * Supported UI languages for the live in-app switcher.
 *
 * `code` is the locale JSON filename under `public/i18n/<code>.json` and the
 * Transloco active-lang id; `name` is the language's own native name shown in
 * the picker. `en` is the source of truth / fallback.
 */
export interface Language {
  code: string;
  /** Endonym — the language's name written in that language. */
  name: string;
}

export const LANGUAGES: Language[] = [
  { code: 'en', name: 'English' },
  { code: 'pl', name: 'Polski' },
  { code: 'es', name: 'Español' },
  { code: 'zh', name: '中文' },
  { code: 'de', name: 'Deutsch' },
  { code: 'fr', name: 'Français' },
  { code: 'it', name: 'Italiano' },
  { code: 'pt', name: 'Português' },
  { code: 'nl', name: 'Nederlands' },
  { code: 'uk', name: 'Українська' },
  { code: 'ru', name: 'Русский' },
  { code: 'tr', name: 'Türkçe' },
  { code: 'ja', name: '日本語' },
  { code: 'ko', name: '한국어' },
];

export const LANGUAGE_CODES: string[] = LANGUAGES.map((l) => l.code);

export const DEFAULT_LANG = 'en';

/** localStorage key holding the user's explicit language choice. */
export const LANG_STORAGE_KEY = 'pdf-conduit.lang';

/**
 * Resolve the initial active language: an explicit stored choice wins; else the
 * best match for `navigator.language` (exact, then base subtag); else English.
 */
export function resolveInitialLang(): string {
  try {
    const stored = localStorage.getItem(LANG_STORAGE_KEY);
    if (stored && LANGUAGE_CODES.includes(stored)) return stored;
  } catch {
    // localStorage may be unavailable (private mode) — fall through.
  }

  if (typeof navigator !== 'undefined') {
    const prefs = [navigator.language, ...(navigator.languages ?? [])].filter(Boolean);
    for (const pref of prefs) {
      const lower = pref.toLowerCase();
      if (LANGUAGE_CODES.includes(lower)) return lower;
      const base = lower.split('-')[0];
      if (LANGUAGE_CODES.includes(base)) return base;
    }
  }
  return DEFAULT_LANG;
}
