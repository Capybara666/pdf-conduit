import {
  AfterViewInit,
  Component,
  ElementRef,
  EventEmitter,
  OnDestroy,
  Output,
  ViewChild,
  computed,
  inject,
  signal,
} from '@angular/core';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { toSignal } from '@angular/core/rxjs-interop';

import { LanguageService } from '../../core/i18n/language.service';
import { Theme, ThemeService } from '../../core/theme.service';
import { DropdownOption, HeaderDropdownComponent } from './dropdown/dropdown.component';

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
 */
@Component({
  selector: 'app-settings-modal',
  standalone: true,
  imports: [TranslocoModule, HeaderDropdownComponent],
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
        <div class="settings-row">
          <span class="settings-label">{{ 'header.languageLabel' | transloco }}</span>
          <app-header-dropdown
            [options]="langOptions"
            [value]="language.active()"
            [ariaLabel]="'header.languageLabel' | transloco"
            [dropUp]="true"
            (valueChange)="onLangChange($event)"
          >
            <svg ddIcon viewBox="0 0 24 24" aria-hidden="true">
              <circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" stroke-width="1.5" />
              <path
                d="M3 12h18 M12 3c3 3 3 15 0 18 M12 3c-3 3-3 15 0 18"
                fill="none"
                stroke="currentColor"
                stroke-width="1.3"
                stroke-linecap="round"
              />
            </svg>
          </app-header-dropdown>
        </div>

        <div class="settings-row">
          <span class="settings-label">{{ 'theme.label' | transloco }}</span>
          <app-header-dropdown
            [options]="themeOptions()"
            [value]="themeService.theme()"
            [ariaLabel]="'theme.label' | transloco"
            [dropUp]="true"
            (valueChange)="onThemeChange($event)"
          >
            <svg ddIcon viewBox="0 0 24 24" aria-hidden="true">
              <path
                d="M12 3a9 9 0 1 0 0 18 2 2 0 0 0 2-2 2 2 0 0 1 2-2h1a4 4 0 0 0 4-4 9 9 0 0 0-9-8z"
                fill="none"
                stroke="currentColor"
                stroke-width="1.5"
                stroke-linejoin="round"
              />
              <circle cx="8" cy="10" r="1" fill="currentColor" />
              <circle cx="12" cy="7.5" r="1" fill="currentColor" />
              <circle cx="16" cy="10" r="1" fill="currentColor" />
            </svg>
          </app-header-dropdown>
        </div>
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
        gap: 1.1rem;
      }

      .settings-row {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 1rem;
      }

      .settings-label {
        font-size: 0.9rem;
        font-weight: 600;
        color: var(--text);
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

  /** Language options (endonyms, so language-agnostic). */
  readonly langOptions: DropdownOption[] = this.language.languages.map((l) => ({
    value: l.code,
    label: l.name,
  }));

  private readonly themeLabels = toSignal(
    this.transloco.selectTranslateObject<Record<string, string>>('theme.name'),
    { initialValue: {} as Record<string, string> },
  );

  readonly themeOptions = computed<DropdownOption[]>(() => {
    const labels = this.themeLabels();
    return this.themeService.themes.map((t) => ({
      value: t,
      label: labels[t] ?? t,
    }));
  });

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
