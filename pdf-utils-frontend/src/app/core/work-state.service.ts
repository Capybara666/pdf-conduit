import {
  DestroyRef,
  Injectable,
  Injector,
  WritableSignal,
  inject,
  isSignal,
} from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { AbstractControl } from '@angular/forms';
import { Observable, merge } from 'rxjs';
import { debounceTime } from 'rxjs/operators';

/**
 * A persistable option field: either a writable signal or a reactive-forms
 * control (`FormControl` / `FormGroup`). Files, results and transient/computed
 * state are intentionally NOT persistable and are never passed here.
 */
export type PersistField = WritableSignal<unknown> | AbstractControl;

/** Namespaced storage key for an operation's saved options. */
const keyFor = (opId: string): string => `pdf-conduit.work.${opId}`;

/** Coalesce rapid edits (typing, dragging a slider) into a single write. */
const SAVE_DEBOUNCE_MS = 250;

interface Registration {
  fields: Record<string, PersistField>;
  /** Snapshot of default values, captured before any saved state is restored. */
  defaults: Record<string, unknown>;
}

/**
 * Remembers each operation page's option/settings values across a page refresh
 * (complaint L23-06) so the user does not have to re-pick everything after a
 * reload. Browser `File` objects cannot be persisted (security), so only the
 * form OPTIONS are stored — files are re-added by the user, results are not kept.
 *
 * Storage is per-tab **sessionStorage**: settings survive a refresh but do not
 * leak into a brand-new tab / session, and everything is wiped when the tab
 * closes. All storage access is defensive (private mode, corrupt JSON, quota):
 * failures are swallowed so persistence never throws into the app.
 *
 * Pages opt in from their constructor (injection context required) with a map
 * of the fields worth remembering:
 *
 * ```ts
 * constructor(private readonly api: ApiService) {
 *   inject(WorkStateService).persist('rotate', { angle: this.angle, pages: this.pages });
 * }
 * ```
 *
 * The explicit "Clear" action (complaint L23-20) calls {@link reset}, which
 * restores the captured defaults and drops the stored entry.
 */
@Injectable({ providedIn: 'root' })
export class WorkStateService {
  private readonly registry = new Map<string, Registration>();

  /**
   * Restore any saved values into `fields`, then keep them saved as they change.
   * Must be called from an injection context (a page constructor).
   */
  persist(opId: string, fields: Record<string, PersistField>): void {
    const injector = inject(Injector);
    const destroyRef = inject(DestroyRef);

    // Capture defaults BEFORE restoring, so Clear can return to a clean slate.
    const defaults = this.snapshot(fields);
    this.registry.set(opId, { fields, defaults });

    this.restore(opId, fields);

    // Watch every field through one debounced stream. `toObservable` emits the
    // current value on subscribe; the debounce coalesces that with real edits.
    const streams: Observable<unknown>[] = Object.values(fields).map((f) =>
      isSignal(f) ? toObservable(f, { injector }) : f.valueChanges,
    );
    if (streams.length) {
      merge(...streams)
        .pipe(debounceTime(SAVE_DEBOUNCE_MS), takeUntilDestroyed(destroyRef))
        .subscribe(() => this.save(opId));
    }
  }

  /** Remove an operation's saved entry from storage (no field changes). */
  clear(opId: string): void {
    try {
      sessionStorage?.removeItem(keyFor(opId));
    } catch {
      // Storage unavailable (private mode / disabled) — nothing to clear.
    }
  }

  /**
   * Reset an operation's persisted fields back to their captured defaults and
   * drop the saved entry. Backs the shared "Clear" button; the page separately
   * clears its files and result. Safe to call for an unregistered id.
   */
  reset(opId: string): void {
    const reg = this.registry.get(opId);
    if (reg) this.apply(reg.fields, reg.defaults);
    this.clear(opId);
  }

  // ---- internals ---------------------------------------------------------

  /** Read the current value of each field into a plain, JSON-serialisable map. */
  private snapshot(fields: Record<string, PersistField>): Record<string, unknown> {
    const out: Record<string, unknown> = {};
    for (const [key, f] of Object.entries(fields)) {
      out[key] = isSignal(f) ? f() : f.value;
    }
    return out;
  }

  /** Write the current field values, or drop the entry once back at defaults. */
  private save(opId: string): void {
    const reg = this.registry.get(opId);
    if (!reg) return;
    const snap = this.snapshot(reg.fields);
    try {
      // At defaults, keep storage clean rather than persisting empty state. This
      // also lets reset() (which restores defaults) settle to a removed entry.
      if (JSON.stringify(snap) === JSON.stringify(reg.defaults)) {
        sessionStorage?.removeItem(keyFor(opId));
      } else {
        sessionStorage?.setItem(keyFor(opId), JSON.stringify(snap));
      }
    } catch {
      // Serialisation or quota failure — skip this save silently.
    }
  }

  /** Load a saved entry (if any) and patch it into the fields, guarding shape. */
  private restore(opId: string, fields: Record<string, PersistField>): void {
    let saved: Record<string, unknown> | null = null;
    try {
      const raw = sessionStorage?.getItem(keyFor(opId));
      if (raw) saved = JSON.parse(raw) as Record<string, unknown>;
    } catch {
      // Missing / corrupt JSON / no storage — start from defaults.
      return;
    }
    if (saved && typeof saved === 'object') this.apply(fields, saved);
  }

  /**
   * Patch `values` into `fields`, field by field. Each field is guarded on its
   * own so a single shape mismatch (an option that changed type since the value
   * was saved) can never break the page or the other fields.
   */
  private apply(fields: Record<string, PersistField>, values: Record<string, unknown>): void {
    for (const [key, f] of Object.entries(fields)) {
      if (!(key in values)) continue;
      const value = values[key];
      try {
        if (isSignal(f)) {
          f.set(value);
        } else {
          // patchValue tolerates partial / extra keys on FormGroups.
          f.patchValue(value as never, { emitEvent: false });
        }
      } catch {
        // Incompatible value for this field — leave it at its default.
      }
    }
  }
}
