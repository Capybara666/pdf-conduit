import { Injectable, signal } from '@angular/core';

export type WidthMode = 'fixed' | 'wide';

/** Both width modes, in the order shown by the header toggle. */
export const WIDTH_MODES: readonly WidthMode[] = ['fixed', 'wide'];

const STORAGE_KEY = 'pdf-conduit.width';

/**
 * Owns the global content width mode. The choice is written to
 * `document.documentElement[data-width]` (the layout CSS keys off it, exactly
 * like `data-theme`) and persisted in localStorage. `fixed` caps the content at
 * a comfortable reading width; `wide` lets it stretch to fill very wide
 * monitors. Any unknown/legacy persisted value falls back to `fixed`.
 *
 * Mirrors {@link ThemeService}'s shape so the two behave identically.
 */
@Injectable({ providedIn: 'root' })
export class WidthService {
  /** Every selectable width mode, for the header toggle. */
  readonly modes = WIDTH_MODES;

  /** Reactive current width mode; components can read it as a signal. */
  readonly width = signal<WidthMode>('fixed');

  constructor() {
    this.width.set(this.initialWidth());
    this.apply(this.width());
  }

  /** Quick affordance: flip between fixed and wide. */
  toggle(): void {
    this.set(this.width() === 'wide' ? 'fixed' : 'wide');
  }

  set(width: WidthMode): void {
    if (!WIDTH_MODES.includes(width)) return;
    this.width.set(width);
    this.apply(width);
    try {
      localStorage.setItem(STORAGE_KEY, width);
    } catch {
      // localStorage may be unavailable (private mode); ignore.
    }
  }

  private initialWidth(): WidthMode {
    try {
      const stored = localStorage.getItem(STORAGE_KEY) as WidthMode | null;
      if (stored && WIDTH_MODES.includes(stored)) {
        return stored;
      }
    } catch {
      // ignore
    }
    return 'fixed';
  }

  private apply(width: WidthMode): void {
    if (typeof document !== 'undefined') {
      document.documentElement.setAttribute('data-width', width);
    }
  }
}
