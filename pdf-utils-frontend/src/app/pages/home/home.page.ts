import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { Subscription } from 'rxjs';

import { NAV_ITEMS, NavItem } from '../../core/operations';
import { ToastService } from '../../core/toast.service';

/**
 * Public landing page — the "advertisement" for PDF Conduit. Hero + value prop,
 * a privacy/trust strip, a feature grid derived from the shared NAV_ITEMS
 * catalog, a "Pro coming soon" teaser, and a footer.
 *
 * Handles its own fragment scrolling (`#tools`, `#pro`) because the app's scroll
 * container is the nested `<main class="content">`, not the window — the router's
 * anchor scroller can't reach it.
 */
@Component({
  selector: 'app-home-page',
  standalone: true,
  imports: [RouterLink, TranslocoModule],
  templateUrl: './home.page.html',
  styleUrl: './home.page.scss',
})
export class HomePage implements OnInit, OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly toasts = inject(ToastService);
  private readonly transloco = inject(TranslocoService);
  private sub?: Subscription;

  /** Primary tools surfaced as large feature cards (curated order). */
  protected readonly featured: NavItem[] = pick([
    'merge',
    'compress',
    'to-pdf',
    'protect',
    'redact',
    'wizard',
  ]);

  /** Every remaining tool, for the "and much more" grid. */
  protected readonly moreTools: NavItem[] = NAV_ITEMS.filter(
    (i) => !this.featured.some((f) => f.id === i.id),
  );

  /** Whether the "Notify me" pro CTA has been clicked (shows confirmation). */
  protected readonly notified = signal(false);

  ngOnInit(): void {
    this.sub = this.route.fragment.subscribe((frag) => {
      if (frag) this.scrollTo(frag);
    });
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
  }

  protected notifyMe(): void {
    this.notified.set(true);
    this.toasts.success(
      this.transloco.translate('home.pro.notifyTitle'),
      this.transloco.translate('home.pro.notifyMessage'),
    );
  }

  private scrollTo(id: string): void {
    // Defer so the target exists after lazy render / navigation.
    setTimeout(() => {
      document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }, 60);
  }
}

/** Resolve a list of NAV_ITEM ids to items, preserving the requested order. */
function pick(ids: string[]): NavItem[] {
  return ids
    .map((id) => NAV_ITEMS.find((i) => i.id === id))
    .filter((i): i is NavItem => i != null);
}
