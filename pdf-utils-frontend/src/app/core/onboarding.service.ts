import { Injectable, signal } from '@angular/core';

const STORAGE_KEY = 'pdf-conduit.onboarding.v1';

/**
 * Tracks whether the one-time welcome onboarding has been dismissed. The choice
 * is persisted in localStorage so the welcome banner is shown at most once per
 * visitor. The `v1` suffix lets a future relaunch re-show the banner once by
 * bumping the key. Storage access is wrapped in try/catch so SSR / private-mode
 * (where localStorage may throw) degrades to an in-memory-only signal.
 */
@Injectable({ providedIn: 'root' })
export class OnboardingService {
  /** Reactive: true once the welcome banner has been dismissed. */
  readonly dismissed = signal<boolean>(this.initialDismissed());

  /** Permanently dismiss the welcome banner (this visitor won't see it again). */
  dismiss(): void {
    this.dismissed.set(true);
    try {
      localStorage.setItem(STORAGE_KEY, '1');
    } catch {
      // localStorage may be unavailable (private mode / SSR); ignore.
    }
  }

  private initialDismissed(): boolean {
    try {
      return localStorage.getItem(STORAGE_KEY) === '1';
    } catch {
      // ignore — treat as not-yet-dismissed
      return false;
    }
  }
}
