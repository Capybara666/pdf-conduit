import {
  Component,
  ElementRef,
  HostListener,
  OnDestroy,
  ViewChild,
  inject,
  signal,
} from '@angular/core';
import { NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { TranslocoModule } from '@jsverse/transloco';
import { Subscription, filter } from 'rxjs';

import { LanguageService } from './core/i18n/language.service';
import { HeaderComponent } from './layout/header/header.component';
import { SidebarComponent } from './layout/sidebar/sidebar.component';
import { ToastContainerComponent } from './shared/toast/toast-container.component';

/**
 * App shell. Owns the mobile off-canvas navigation state: below the mobile
 * breakpoint the sidebar becomes a focus-trapped drawer toggled from the
 * header hamburger, dimmed behind a scrim. Also drives the skip-to-content
 * link by moving focus to <main> on every navigation.
 */
@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    RouterOutlet,
    HeaderComponent,
    SidebarComponent,
    ToastContainerComponent,
    TranslocoModule,
  ],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss',
})
export class AppComponent implements OnDestroy {
  // Eagerly instantiate so <html lang> is set and the active language is
  // tracked from app start (before the header picker is interacted with).
  private readonly language = inject(LanguageService);
  private readonly router = inject(Router);

  @ViewChild(HeaderComponent) private header?: HeaderComponent;
  @ViewChild(SidebarComponent, { read: ElementRef })
  private sidebarRef?: ElementRef<HTMLElement>;
  @ViewChild('content') private contentRef?: ElementRef<HTMLElement>;

  /** Whether the mobile off-canvas drawer is open. Always false on desktop. */
  readonly drawerOpen = signal(false);

  private readonly navSub: Subscription;
  private readonly mql =
    typeof window !== 'undefined' ? window.matchMedia('(max-width: 820px)') : null;
  private readonly onMqlChange = (e: MediaQueryListEvent): void => {
    // Leaving the mobile range: drop any drawer state so focus/scroll locks
    // never linger over the permanent desktop rail.
    if (!e.matches) this.closeDrawer(false);
  };

  constructor() {
    this.mql?.addEventListener('change', this.onMqlChange);
    this.navSub = this.router.events
      .pipe(filter((e): e is NavigationEnd => e instanceof NavigationEnd))
      .subscribe(() => {
        // Close the drawer without stealing focus back to the hamburger...
        this.closeDrawer(false);
        // ...and hand focus to the main region so keyboard/SR users land on
        // the new page instead of re-tabbing the whole nav (skip-link intent).
        this.contentRef?.nativeElement.focus({ preventScroll: true });
      });
  }

  ngOnDestroy(): void {
    this.navSub.unsubscribe();
    this.mql?.removeEventListener('change', this.onMqlChange);
  }

  toggleDrawer(): void {
    this.drawerOpen() ? this.closeDrawer() : this.openDrawer();
  }

  openDrawer(): void {
    this.drawerOpen.set(true);
    // Move focus into the drawer once it has rendered/opened.
    setTimeout(() => this.focusFirstInDrawer());
  }

  /**
   * @param returnFocus move focus back to the hamburger (true for user-driven
   * close: scrim/Escape/toggle; false for navigation where focus goes to main).
   */
  closeDrawer(returnFocus = true): void {
    if (!this.drawerOpen()) return;
    this.drawerOpen.set(false);
    if (returnFocus) this.header?.focusMenu();
  }

  @HostListener('document:keydown', ['$event'])
  onKeydown(event: KeyboardEvent): void {
    if (!this.drawerOpen()) return;

    if (event.key === 'Escape') {
      event.preventDefault();
      this.closeDrawer();
      return;
    }

    if (event.key === 'Tab') this.trapFocus(event);
  }

  /** Keep Tab / Shift+Tab cycling within the open drawer. */
  private trapFocus(event: KeyboardEvent): void {
    const items = this.drawerFocusables();
    if (items.length === 0) return;
    const first = items[0];
    const last = items[items.length - 1];
    const active = document.activeElement;
    const inside = this.sidebarRef?.nativeElement.contains(active) ?? false;

    if (event.shiftKey) {
      if (!inside || active === first) {
        event.preventDefault();
        last.focus();
      }
    } else if (!inside || active === last) {
      event.preventDefault();
      first.focus();
    }
  }

  private focusFirstInDrawer(): void {
    this.drawerFocusables()[0]?.focus();
  }

  private drawerFocusables(): HTMLElement[] {
    const root = this.sidebarRef?.nativeElement;
    if (!root) return [];
    return Array.from(
      root.querySelectorAll<HTMLElement>('a[href], button:not([disabled])'),
    );
  }
}
