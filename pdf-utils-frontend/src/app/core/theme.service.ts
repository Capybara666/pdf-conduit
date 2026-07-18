import { Injectable, signal } from '@angular/core';

export type Theme = 'light' | 'dark' | 'nord' | 'dracula' | 'solarized' | 'sunset';

/** All selectable themes, in the order shown by the header picker. */
export const THEMES: readonly Theme[] = ['light', 'dark', 'nord', 'dracula', 'solarized', 'sunset'];

const STORAGE_KEY = 'pdf-conduit.theme';

/**
 * Owns the active colour theme. The choice is written to
 * `document.documentElement[data-theme]` (styles.scss keys off it) and
 * persisted in localStorage. Six themes are available (light, dark, nord,
 * dracula, solarized, sunset); light/dark are the OS-preference default, the
 * others are explicit opt-in. Any unknown/legacy persisted value falls back to
 * the OS preference.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  /** Every selectable theme, for the header picker. */
  readonly themes = THEMES;

  /** Reactive current theme; components can read it as a signal. */
  readonly theme = signal<Theme>('light');

  constructor() {
    this.theme.set(this.initialTheme());
    this.apply(this.theme());
  }

  /** Quick affordance: flip between light and dark regardless of current theme. */
  toggle(): void {
    this.set(this.theme() === 'dark' ? 'light' : 'dark');
  }

  set(theme: Theme): void {
    if (!THEMES.includes(theme)) return;
    this.theme.set(theme);
    this.apply(theme);
    try {
      localStorage.setItem(STORAGE_KEY, theme);
    } catch {
      // localStorage may be unavailable (private mode); ignore.
    }
  }

  private initialTheme(): Theme {
    try {
      const stored = localStorage.getItem(STORAGE_KEY) as Theme | null;
      if (stored && THEMES.includes(stored)) {
        return stored;
      }
    } catch {
      // ignore
    }
    if (typeof window !== 'undefined' && window.matchMedia) {
      return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
    }
    return 'light';
  }

  private apply(theme: Theme): void {
    if (typeof document !== 'undefined') {
      document.documentElement.setAttribute('data-theme', theme);
    }
  }
}
