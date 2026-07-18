import { Injectable, inject } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { RouterStateSnapshot, TitleStrategy } from '@angular/router';
import { TranslocoService } from '@jsverse/transloco';
import { Subscription } from 'rxjs';

/** Un-translated product name; always the trailing part of the tab title. */
const BRAND = 'PDF Conduit';

/**
 * Builds the browser tab title from a Transloco key declared on each route
 * (`data.titleKey`, typically an `op.<id>.label` or `nav.*` key) so the tab
 * localizes with the rest of the UI. Routes without a `titleKey` (the landing
 * page) show the bare brand.
 *
 * The title tracks `selectTranslate(key)`, which emits the translated label and
 * RE-EMITS on every language change once the new locale's dictionary has loaded
 * — so a live language switch (not just a navigation) updates the tab in place.
 * Using `selectTranslate` rather than a `langChanges$` + `translate()` pair
 * avoids reading a stale value before the lazily-fetched locale JSON arrives.
 */
@Injectable({ providedIn: 'root' })
export class TranslatedTitleStrategy extends TitleStrategy {
  private readonly title = inject(Title);
  private readonly transloco = inject(TranslocoService);

  /** Live subscription to the active route's translated title. */
  private titleSub?: Subscription;

  override updateTitle(snapshot: RouterStateSnapshot): void {
    const key = this.titleKeyFor(snapshot);
    this.titleSub?.unsubscribe();

    if (!key) {
      this.title.setTitle(BRAND);
      return;
    }
    // selectTranslate emits now and again on each subsequent (loaded) lang change.
    this.titleSub = this.transloco
      .selectTranslate(key)
      .subscribe((label) =>
        this.title.setTitle(label ? `${label} · ${BRAND}` : BRAND),
      );
  }

  /** Walk to the deepest activated route that declares a `titleKey`. */
  private titleKeyFor(snapshot: RouterStateSnapshot): string | undefined {
    let route = snapshot.root;
    let key: string | undefined;
    while (route) {
      const routeKey = route.data?.['titleKey'];
      if (typeof routeKey === 'string' && routeKey) {
        key = routeKey;
      }
      if (!route.firstChild) break;
      route = route.firstChild;
    }
    return key;
  }
}
