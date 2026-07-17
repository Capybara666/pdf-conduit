import { Injectable, signal } from '@angular/core';

export type Theme = 'light' | 'dark';

const STORAGE_KEY = 'pdf-conduit.theme';

/**
 * Owns the light/dark theme. The active theme is written to
 * `document.documentElement[data-theme]` (styles.scss keys off it) and
 * persisted in localStorage. Initial value: stored preference, else the OS
 * `prefers-color-scheme`.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  /** Reactive current theme; components can read it as a signal. */
  readonly theme = signal<Theme>('light');

  constructor() {
    this.theme.set(this.initialTheme());
    this.apply(this.theme());
  }

  toggle(): void {
    this.set(this.theme() === 'dark' ? 'light' : 'dark');
  }

  set(theme: Theme): void {
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
      const stored = localStorage.getItem(STORAGE_KEY);
      if (stored === 'light' || stored === 'dark') {
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
