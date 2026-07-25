import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';

import { LanguageService } from '../../core/i18n/language.service';
import { Theme, ThemeService } from '../../core/theme.service';
import { DropdownOption, HeaderDropdownComponent } from './dropdown/dropdown.component';

/**
 * The header's secondary pickers (language + theme), extracted into a small
 * self-contained control. Rendered inline in the desktop header
 * (`.header-actions`); below the mobile breakpoint it is hidden and the same
 * two pickers are offered from the settings sheet (gear button) instead, so the
 * top bar never overflows into an ugly multi-row cluster on narrow viewports.
 *
 * Binds to the same root-provided services as the settings sheet, so switching
 * language or theme from either surface stays in sync. Host is an inline flex
 * row with the same 1rem gap the header used, so the desktop layout is unchanged.
 */
@Component({
  selector: 'app-secondary-controls',
  standalone: true,
  imports: [TranslocoModule, HeaderDropdownComponent],
  template: `
    <app-header-dropdown
      [options]="langOptions"
      [value]="language.active()"
      [ariaLabel]="'header.languageLabel' | transloco"
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

    <!--
      Theme dropdown: no leading icon — the two-tone colour swatch (rendered by
      the dropdown for each themed option and on the trigger) already conveys
      "theme", so a separate palette glyph would be redundant. The empty icon slot
      collapses via .dd-icon:empty, so the trigger stays tight to the swatch.
    -->
    <app-header-dropdown
      [options]="themeOptions()"
      [value]="themeService.theme()"
      [ariaLabel]="'theme.label' | transloco"
      (valueChange)="onThemeChange($event)"
    ></app-header-dropdown>
  `,
  styles: [
    `
      :host {
        display: inline-flex;
        align-items: center;
        flex-wrap: wrap;
        gap: 1rem;
      }
    `,
  ],
})
export class SecondaryControlsComponent {
  protected readonly themeService = inject(ThemeService);
  protected readonly language = inject(LanguageService);
  private readonly transloco = inject(TranslocoService);

  /** Language options for the custom dropdown (endonyms, so language-agnostic). */
  readonly langOptions: DropdownOption[] = this.language.languages.map((l) => ({
    value: l.code,
    label: l.name,
  }));

  /**
   * Translated theme labels, kept as a signal that updates when the locale
   * dictionary finishes loading AND on every language switch. `selectTranslate*`
   * is reactive (unlike the synchronous `translate()`, which returns the raw key
   * if read before the async dictionary has loaded and never self-heals).
   */
  private readonly themeLabels = toSignal(
    this.transloco.selectTranslateObject<Record<string, string>>('theme.name'),
    { initialValue: {} as Record<string, string> },
  );

  /**
   * Per-theme two-tone swatch colours `[background, accent]`, kept identical to
   * the mobile settings sheet's theme-chip swatches so desktop and mobile match.
   */
  private static readonly THEME_SWATCHES: Record<string, [string, string]> = {
    light: ['#ffffff', '#2563eb'],
    dark: ['#1e2430', '#60a5fa'],
    nord: ['#2e3440', '#88c0d0'],
    dracula: ['#282a36', '#bd93f9'],
    solarized: ['#fdf6e3', '#268bd2'],
    sunset: ['#2b1b2e', '#ff7e5f'],
  };

  /** Theme options for the custom dropdown; labels track the active UI language. */
  readonly themeOptions = computed<DropdownOption[]>(() => {
    const labels = this.themeLabels();
    return this.themeService.themes.map((t) => ({
      value: t,
      label: labels[t] ?? t,
      swatch: SecondaryControlsComponent.THEME_SWATCHES[t],
    }));
  });

  onThemeChange(value: string): void {
    this.themeService.set(value as Theme);
  }

  onLangChange(value: string): void {
    this.language.setLang(value);
  }
}
