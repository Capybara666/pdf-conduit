import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';

import { AppComponent } from './app.component';
import { routes } from './app.routes';
import { TRANSLOCO_TESTING_PROVIDERS, translocoTesting } from './testing/transloco-testing';

describe('AppComponent', () => {
  let fixture: ComponentFixture<AppComponent>;
  let host: HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AppComponent, translocoTesting()],
      providers: [
        provideRouter(routes),
        provideHttpClient(),
        provideHttpClientTesting(),
        TRANSLOCO_TESTING_PROVIDERS,
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AppComponent);
    host = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  });

  it('should create the app shell', () => {
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('renders the shell landmarks (header, sidebar, main, outlet, toasts)', () => {
    expect(host.querySelector('app-header')).withContext('header').toBeTruthy();
    expect(host.querySelector('app-sidebar')).withContext('sidebar').toBeTruthy();
    expect(host.querySelector('app-toast-container')).withContext('toasts').toBeTruthy();

    const main = host.querySelector('main#content');
    expect(main).withContext('main#content').toBeTruthy();
    // Programmatically focusable so the skip link / navigation can move focus here.
    expect(main?.getAttribute('tabindex')).toBe('-1');
    expect(main?.querySelector('router-outlet')).withContext('router-outlet').toBeTruthy();
  });

  it('translates the skip link and points it at the main region', () => {
    const skip = host.querySelector<HTMLAnchorElement>('a.skip-link');
    expect(skip).toBeTruthy();
    expect(skip?.getAttribute('href')).toBe('#content');
    // Real en.json copy — a missing key would leave the raw key in the DOM.
    expect(skip?.textContent?.trim()).toBe('Skip to content');
  });

  it('reflects the active language on <html lang>', () => {
    expect(document.documentElement.getAttribute('lang')).toBe('en');
  });

  it('opens and closes the mobile drawer, toggling the body scroll lock', () => {
    const body = host.querySelector('.body') as HTMLElement;
    expect(fixture.componentInstance.drawerOpen()).toBe(false);
    expect(body.classList.contains('drawer-open')).toBe(false);

    fixture.componentInstance.toggleDrawer();
    fixture.detectChanges();
    expect(fixture.componentInstance.drawerOpen()).toBe(true);
    expect(body.classList.contains('drawer-open')).toBe(true);
    expect(document.documentElement.classList.contains('drawer-scroll-lock')).toBe(true);

    fixture.componentInstance.toggleDrawer();
    fixture.detectChanges();
    expect(fixture.componentInstance.drawerOpen()).toBe(false);
    expect(body.classList.contains('drawer-open')).toBe(false);
    expect(document.documentElement.classList.contains('drawer-scroll-lock')).toBe(false);
  });

  it('closes the open drawer on Escape and on a scrim click', () => {
    fixture.componentInstance.openDrawer();
    fixture.detectChanges();

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    fixture.detectChanges();
    expect(fixture.componentInstance.drawerOpen()).withContext('after Escape').toBe(false);

    fixture.componentInstance.openDrawer();
    fixture.detectChanges();
    (host.querySelector('.scrim') as HTMLElement).click();
    fixture.detectChanges();
    expect(fixture.componentInstance.drawerOpen()).withContext('after scrim click').toBe(false);
  });

  it('releases the body scroll lock when the shell is destroyed mid-open', () => {
    fixture.componentInstance.openDrawer();
    fixture.detectChanges();
    expect(document.documentElement.classList.contains('drawer-scroll-lock')).toBe(true);

    fixture.destroy();
    expect(document.documentElement.classList.contains('drawer-scroll-lock')).toBe(false);
  });
});
