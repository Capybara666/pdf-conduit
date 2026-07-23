import {
  AfterViewInit,
  Component,
  ElementRef,
  EventEmitter,
  OnDestroy,
  Output,
  ViewChild,
  inject,
  signal,
} from '@angular/core';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { toSignal } from '@angular/core/rxjs-interop';

import { LanguageService } from '../../core/i18n/language.service';
import { Theme, ThemeService } from '../../core/theme.service';

/**
 * Mobile settings sheet. Below the rail breakpoint the header hides its inline
 * language + theme pickers to keep the top bar to one row; a gear button opens
 * this modal, which becomes the single, findable home for those settings on
 * phones (they no longer live at the foot of the nav drawer).
 *
 * Accessibility: `role="dialog"` + `aria-modal`, focus moved into the sheet on
 * open and a Tab focus trap kept inside it, Escape / backdrop-click / close
 * button all dismiss, and the page is scroll-locked (`drawer-scroll-lock`, the
 * same root class the nav drawer uses) while it is open. The host owns the open
 * state and only renders this component while open, so mount = open.
 *
 * The theme + language pickers are rendered as INLINE controls (a wrap of theme
 * chips and a scrollable radio-style language list) rather than popup dropdowns:
 * a popup inside a bottom sheet either overflows the viewport (drop-down) or is
 * clipped by the sheet (drop-up). Inline controls live in normal flow, so they
 * can never be clipped — if the combined content exceeds the sheet height the
 * modal body itself scrolls (`overflow-y: auto` on `.settings-panel`), keeping
 * every option reachable.
 */
@Component({
  selector: 'app-settings-modal',
  standalone: true,
  imports: [TranslocoModule],
  template: `
    <div class="settings-backdrop" (click)="requestClose()"></div>

    <div
      #panel
      class="settings-panel"
      role="dialog"
      aria-modal="true"
      [attr.aria-label]="'header.settings' | transloco"
      (keydown)="onKeydown($event)"
    >
      <div class="settings-head">
        <h2 class="settings-title">{{ 'header.settings' | transloco }}</h2>
        <button
          #closeBtn
          type="button"
          class="settings-close"
          (click)="requestClose()"
          [attr.aria-label]="'header.settingsClose' | transloco"
        >
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path
              d="M6 6l12 12 M18 6 6 18"
              stroke="currentColor"
              stroke-width="1.8"
              stroke-linecap="round"
            />
          </svg>
        </button>
      </div>

      <div class="settings-body">
        <!--
          Theme: an inline wrap of chips (one per theme). No popup, so it can
          never overflow the viewport or be clipped by the sheet. The active
          chip is highlighted and marked aria-pressed.
        -->
        <section class="settings-group" role="group" [attr.aria-label]="'theme.label' | transloco">
          <span class="settings-label">{{ 'theme.label' | transloco }}</span>
          <div class="theme-chips">
            @for (t of themeService.themes; track t) {
              <button
                type="button"
                class="theme-chip"
                [class.active]="themeService.theme() === t"
                [attr.aria-pressed]="themeService.theme() === t"
                (click)="onThemeChange(t)"
              >
                <span class="theme-swatch" [attr.data-theme]="t" aria-hidden="true">
                  <span class="dot dot-bg"></span>
                  <span class="dot dot-accent"></span>
                </span>
                <span class="theme-chip-label">{{ themeLabel(t) }}</span>
              </button>
            }
          </div>
        </section>

        <!--
          Language: an inline, vertically-scrollable radio-style list (one row
          per language, endonyms). Inline in normal flow — if the list is taller
          than its cap it scrolls internally; if the whole sheet is too tall the
          modal body scrolls. Either way nothing pops out to be clipped.
        -->
        <section
          class="settings-group"
          role="radiogroup"
          [attr.aria-label]="'header.languageLabel' | transloco"
        >
          <span class="settings-label">{{ 'header.languageLabel' | transloco }}</span>
          <div class="lang-list">
            @for (l of language.languages; track l.code) {
              <button
                type="button"
                class="lang-option"
                role="radio"
                [class.active]="language.active() === l.code"
                [attr.aria-checked]="language.active() === l.code"
                (click)="onLangChange(l.code)"
              >
                <span class="lang-name">{{ l.name }}</span>
                @if (language.active() === l.code) {
                  <svg class="lang-check" viewBox="0 0 24 24" aria-hidden="true">
                    <path
                      d="M5 12.5l4.5 4.5L19 7"
                      fill="none"
                      stroke="currentColor"
                      stroke-width="2"
                      stroke-linecap="round"
                      stroke-linejoin="round"
                    />
                  </svg>
                }
              </button>
            }
          </div>
        </section>
      </div>
    </div>
  `,
  styles: [
    `
      :host {
        position: fixed;
        inset: 0;
        z-index: 80;
        display: flex;
        align-items: flex-end;
        justify-content: center;
      }

      .settings-backdrop {
        position: absolute;
        inset: 0;
        background: rgba(0, 0, 0, 0.45);
      }

      .settings-panel {
        position: relative;
        width: 100%;
        max-width: 420px;
        max-height: 85vh;
        overflow-y: auto;
        overscroll-behavior: contain;
        padding: 1rem 1.25rem 1.5rem;
        background: var(--surface);
        border: 1px solid var(--border);
        border-radius: var(--radius) var(--radius) 0 0;
        box-shadow: var(--shadow);
      }

      @media (min-width: 480px) {
        :host {
          align-items: center;
        }
        .settings-panel {
          border-radius: var(--radius);
        }
      }

      .settings-head {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 1rem;
        margin-bottom: 1rem;
      }

      .settings-title {
        margin: 0;
        font-size: 1.05rem;
        font-weight: 700;
        color: var(--text);
      }

      .settings-close {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        width: 2.25rem;
        height: 2.25rem;
        border: 1px solid var(--border);
        border-radius: calc(var(--radius) - 4px);
        background: var(--surface-2);
        color: var(--text);
        cursor: pointer;
        transition: border-color var(--dur-base) var(--ease-standard),
          color var(--dur-base) var(--ease-standard);

        svg {
          width: 1.35rem;
          height: 1.35rem;
          fill: none;
        }

        &:hover,
        &:focus-visible {
          border-color: var(--accent);
          color: var(--accent);
        }
      }

      .settings-body {
        display: flex;
        flex-direction: column;
        gap: 1.5rem;
      }

      .settings-group {
        display: flex;
        flex-direction: column;
        gap: 0.6rem;
      }

      .settings-label {
        font-size: 0.9rem;
        font-weight: 600;
        color: var(--text);
      }

      /* Theme chips — inline wrap, no popup so nothing can be clipped. */
      .theme-chips {
        display: flex;
        flex-wrap: wrap;
        gap: 0.5rem;
      }

      .theme-chip {
        display: inline-flex;
        align-items: center;
        gap: 0.5rem;
        padding: 0.4rem 0.7rem;
        border: 1px solid var(--border);
        border-radius: calc(var(--radius) - 4px);
        background: var(--surface-2);
        color: var(--text);
        font-size: 0.85rem;
        font-weight: 600;
        cursor: pointer;
        transition: border-color var(--dur-base) var(--ease-standard),
          color var(--dur-base) var(--ease-standard),
          background var(--dur-base) var(--ease-standard);
      }

      .theme-chip:hover,
      .theme-chip:focus-visible {
        border-color: var(--accent);
        color: var(--accent);
      }

      .theme-chip.active {
        border-color: var(--accent);
        color: var(--accent);
        background: color-mix(in srgb, var(--accent) 12%, var(--surface-2));
      }

      .theme-swatch {
        position: relative;
        display: inline-block;
        width: 1.1rem;
        height: 1.1rem;
        border-radius: 999px;
        overflow: hidden;
        border: 1px solid var(--border);
        flex: none;
      }

      .theme-swatch .dot {
        position: absolute;
        inset: 0;
        display: block;
      }

      .theme-swatch .dot-accent {
        left: 50%;
      }

      /* Per-theme swatch preview colours (background + accent halves). */
      .theme-swatch[data-theme='light'] .dot-bg {
        background: #ffffff;
      }
      .theme-swatch[data-theme='light'] .dot-accent {
        background: #2563eb;
      }
      .theme-swatch[data-theme='dark'] .dot-bg {
        background: #1e2430;
      }
      .theme-swatch[data-theme='dark'] .dot-accent {
        background: #60a5fa;
      }
      .theme-swatch[data-theme='nord'] .dot-bg {
        background: #2e3440;
      }
      .theme-swatch[data-theme='nord'] .dot-accent {
        background: #88c0d0;
      }
      .theme-swatch[data-theme='dracula'] .dot-bg {
        background: #282a36;
      }
      .theme-swatch[data-theme='dracula'] .dot-accent {
        background: #bd93f9;
      }
      .theme-swatch[data-theme='solarized'] .dot-bg {
        background: #fdf6e3;
      }
      .theme-swatch[data-theme='solarized'] .dot-accent {
        background: #268bd2;
      }
      .theme-swatch[data-theme='sunset'] .dot-bg {
        background: #2b1b2e;
      }
      .theme-swatch[data-theme='sunset'] .dot-accent {
        background: #ff7e5f;
      }

      /* Language list — inline, vertically scrollable; never a popup. */
      .lang-list {
        display: flex;
        flex-direction: column;
        gap: 0.25rem;
        max-height: 40vh;
        overflow-y: auto;
        overscroll-behavior: contain;
        border: 1px solid var(--border);
        border-radius: calc(var(--radius) - 4px);
        padding: 0.25rem;
      }

      .lang-option {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 0.75rem;
        width: 100%;
        padding: 0.55rem 0.7rem;
        border: 1px solid transparent;
        border-radius: calc(var(--radius) - 6px);
        background: transparent;
        color: var(--text);
        font-size: 0.9rem;
        text-align: left;
        cursor: pointer;
        transition: border-color var(--dur-base) var(--ease-standard),
          color var(--dur-base) var(--ease-standard),
          background var(--dur-base) var(--ease-standard);
      }

      .lang-option:hover,
      .lang-option:focus-visible {
        border-color: var(--accent);
        color: var(--accent);
      }

      .lang-option.active {
        color: var(--accent);
        font-weight: 700;
        background: color-mix(in srgb, var(--accent) 12%, transparent);
      }

      .lang-check {
        width: 1.1rem;
        height: 1.1rem;
        flex: none;
      }
    `,
  ],
})
export class SettingsModalComponent implements AfterViewInit, OnDestroy {
  protected readonly themeService = inject(ThemeService);
  protected readonly language = inject(LanguageService);
  private readonly transloco = inject(TranslocoService);

  /** Emitted when the sheet asks to close (Escape / backdrop / close button). */
  @Output() closed = new EventEmitter<void>();

  @ViewChild('panel') private panel?: ElementRef<HTMLElement>;
  @ViewChild('closeBtn') private closeBtn?: ElementRef<HTMLButtonElement>;

  /**
   * Translated theme display names, kept as a signal that updates when the
   * locale dictionary finishes loading AND on every language switch.
   * `selectTranslate*` is reactive (unlike the synchronous `translate()`, which
   * returns the raw key if read before the async dictionary has loaded).
   */
  private readonly themeLabels = toSignal(
    this.transloco.selectTranslateObject<Record<string, string>>('theme.name'),
    { initialValue: {} as Record<string, string> },
  );

  /** Human name for a theme chip, falling back to the id before dictionaries load. */
  themeLabel(theme: Theme): string {
    return this.themeLabels()[theme] ?? theme;
  }

  private readonly wasLocked = signal(false);

  ngAfterViewInit(): void {
    // Scroll-lock the page behind the sheet (same root class the nav drawer
    // uses) and move focus to the close button so keyboard/SR users land here.
    if (typeof document !== 'undefined') {
      this.wasLocked.set(
        document.documentElement.classList.contains('drawer-scroll-lock'),
      );
      document.documentElement.classList.add('drawer-scroll-lock');
    }
    setTimeout(() => this.closeBtn?.nativeElement.focus());
  }

  ngOnDestroy(): void {
    // Only release the lock if we were the one who took it (never fight the
    // drawer if it happened to be open too).
    if (typeof document !== 'undefined' && !this.wasLocked()) {
      document.documentElement.classList.remove('drawer-scroll-lock');
    }
  }

  requestClose(): void {
    this.closed.emit();
  }

  onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      this.requestClose();
      return;
    }
    if (event.key === 'Tab') this.trapFocus(event);
  }

  /** Keep Tab / Shift+Tab cycling within the open sheet. */
  private trapFocus(event: KeyboardEvent): void {
    const root = this.panel?.nativeElement;
    if (!root) return;
    const items = Array.from(
      root.querySelectorAll<HTMLElement>('a[href], button:not([disabled]), [tabindex]:not([tabindex="-1"])'),
    ).filter((el) => el.offsetParent !== null);
    if (items.length === 0) return;
    const first = items[0];
    const last = items[items.length - 1];
    const active = document.activeElement;
    const inside = root.contains(active);

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

  onThemeChange(value: string): void {
    this.themeService.set(value as Theme);
  }

  onLangChange(value: string): void {
    this.language.setLang(value);
  }
}
