import { Injectable } from '@angular/core';

import { RedactRegion } from './api.models';

/** A file plus the regions to pre-draw over it when the redact page opens. */
export interface RedactHandoff {
  file: File;
  regions: RedactRegion[];
}

/**
 * One-shot channel that carries a scanned `File` + its detected regions from the
 * GDPR scan report to the Redact page. A `File` can't ride the URL, so the
 * report stashes it here and navigates to `/redact`; the redact page `consume()`s
 * it exactly once on init. Consuming clears the slot so a later plain visit to
 * `/redact` starts blank.
 */
@Injectable({ providedIn: 'root' })
export class RedactHandoffService {
  private pending: RedactHandoff | null = null;

  /** Stash a file + regions for the next `/redact` visit. */
  set(file: File, regions: RedactRegion[]): void {
    this.pending = { file, regions };
  }

  /** Whether a handoff is waiting to be consumed. */
  hasPending(): boolean {
    return this.pending !== null;
  }

  /** Take and clear the pending handoff (returns null if none). */
  consume(): RedactHandoff | null {
    const p = this.pending;
    this.pending = null;
    return p;
  }
}
