import {
  Component,
  ElementRef,
  EventEmitter,
  HostListener,
  Input,
  Output,
  QueryList,
  ViewChild,
  ViewChildren,
  inject,
  signal,
} from '@angular/core';

/** One selectable entry in the dropdown. */
export interface DropdownOption {
  value: string;
  label: string;
  /**
   * Optional decorative two-tone colour swatch `[background, accent]`, rendered
   * as a small rounded pill split down the middle (left = background half,
   * right = accent half) before the label — matching the mobile settings sheet
   * theme chips. Omit it (e.g. the language dropdown) and no swatch is drawn.
   */
  swatch?: [string, string];
}

/**
 * A small, self-contained, accessible dropdown used by the header for the theme
 * and language pickers. Unlike a native `<select>` wrapped in a pill (which only
 * opens when the inner text is clicked), the ENTIRE trigger pill is a real
 * button, so clicking anywhere on it opens the menu.
 *
 * Accessibility (WAI-ARIA listbox pattern):
 *  - the trigger is a `<button aria-haspopup="listbox" aria-expanded>`;
 *  - the popup is a `role="listbox"` whose entries are `role="option"` with
 *    `aria-selected`;
 *  - keyboard: Enter/Space/ArrowDown/ArrowUp open the menu from the trigger;
 *    within the menu ArrowUp/ArrowDown/Home/End move focus, Enter/Space pick the
 *    focused option, Escape (or Tab) closes and returns focus to the trigger;
 *  - clicking outside closes the menu;
 *  - focus is moved into the menu on open and back to the trigger on close.
 *
 * Themed entirely with the app's existing CSS variables. Emits `valueChange`
 * with the chosen option's value; the host owns the actual state.
 */
@Component({
  selector: 'app-header-dropdown',
  standalone: true,
  imports: [],
  template: `
    <button
      #trigger
      type="button"
      class="dd-trigger"
      [attr.aria-label]="ariaLabel"
      aria-haspopup="listbox"
      [attr.aria-expanded]="open()"
      (click)="toggle()"
      (keydown)="onTriggerKeydown($event)"
    >
      <span class="dd-icon" aria-hidden="true"><ng-content select="[ddIcon]"></ng-content></span>
      @if (selectedSwatch; as sw) {
        <span class="dd-swatch" aria-hidden="true">
          <span class="dd-dot" [style.background]="sw[0]"></span>
          <span class="dd-dot dd-dot-accent" [style.background]="sw[1]"></span>
        </span>
      }
      <span class="dd-label">{{ selectedLabel }}</span>
      <svg class="dd-caret" viewBox="0 0 24 24" aria-hidden="true">
        <path
          d="M6 9l6 6 6-6"
          fill="none"
          stroke="currentColor"
          stroke-width="1.8"
          stroke-linecap="round"
          stroke-linejoin="round"
        />
      </svg>
    </button>

    @if (open()) {
      <ul
        #listbox
        class="dd-menu"
        [class.dd-menu-up]="dropUp"
        role="listbox"
        [attr.aria-label]="ariaLabel"
        (keydown)="onListKeydown($event)"
      >
        @for (opt of options; track opt.value; let i = $index) {
          <li
            #option
            class="dd-option"
            role="option"
            tabindex="-1"
            [class.active]="i === activeIndex()"
            [attr.aria-selected]="opt.value === value"
            (click)="select(opt)"
          >
            <svg class="dd-check" viewBox="0 0 24 24" aria-hidden="true">
              @if (opt.value === value) {
                <path
                  d="M5 12.5l4.5 4.5L19 7"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                />
              }
            </svg>
            @if (opt.swatch; as sw) {
              <span class="dd-swatch" aria-hidden="true">
                <span class="dd-dot" [style.background]="sw[0]"></span>
                <span class="dd-dot dd-dot-accent" [style.background]="sw[1]"></span>
              </span>
            }
            <span class="dd-option-label">{{ opt.label }}</span>
          </li>
        }
      </ul>
    }
  `,
  styles: [
    `
      :host {
        position: relative;
        display: inline-block;
      }

      .dd-trigger {
        display: inline-flex;
        align-items: center;
        gap: 0.35rem;
        height: 2.25rem;
        padding: 0 0.55rem;
        border: 1px solid var(--border);
        border-radius: 999px;
        background: var(--surface-2);
        color: var(--text);
        font-size: 0.85rem;
        font-family: inherit;
        cursor: pointer;
        transition: border-color var(--dur-base) var(--ease-standard),
          color var(--dur-base) var(--ease-standard);
      }

      .dd-trigger:hover,
      .dd-trigger:focus-visible {
        border-color: var(--accent);
      }

      .dd-trigger:focus-visible {
        outline: 2px solid var(--accent);
        outline-offset: 2px;
      }

      .dd-icon {
        display: inline-flex;
        align-items: center;
        color: var(--text-muted);
      }

      /* Collapse the icon slot entirely when no icon is projected (e.g. the
         theme dropdown, whose swatch now conveys "theme"). Without this an
         empty flex child would still contribute the trigger's 0.35rem gap,
         leaving a dead space before the swatch. */
      .dd-icon:empty {
        display: none;
      }

      .dd-icon ::ng-deep svg {
        width: 1.15rem;
        height: 1.15rem;
        display: block;
      }

      .dd-label {
        max-width: 8rem;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      .dd-caret {
        width: 1rem;
        height: 1rem;
        color: var(--text-muted);
        flex-shrink: 0;
      }

      .dd-menu {
        position: absolute;
        top: calc(100% + 0.35rem);
        right: 0;
        z-index: 60;
        min-width: 100%;
        max-height: 60vh;
        overflow-y: auto;
        margin: 0;
        padding: 0.3rem;
        list-style: none;
        border: 1px solid var(--border);
        border-radius: calc(var(--radius) - 2px);
        background: var(--surface);
        box-shadow: 0 10px 30px rgba(0, 0, 0, 0.18);
      }

      /* Open above the trigger — used inside the mobile settings bottom-sheet,
         where a downward menu would run off the bottom of the screen. Cap the
         height so it stays within the sheet and scrolls on its own. */
      .dd-menu-up {
        top: auto;
        bottom: calc(100% + 0.35rem);
        max-height: 40vh;
      }

      .dd-option {
        display: flex;
        align-items: center;
        gap: 0.4rem;
        padding: 0.4rem 0.6rem;
        border-radius: calc(var(--radius) - 6px);
        color: var(--text);
        font-size: 0.85rem;
        white-space: nowrap;
        cursor: pointer;
        transition: background var(--dur-base) var(--ease-standard);
      }

      .dd-option:hover,
      .dd-option.active {
        background: var(--surface-2);
      }

      .dd-option[aria-selected='true'] {
        color: var(--accent);
      }

      .dd-option:focus-visible {
        outline: 2px solid var(--accent);
        outline-offset: -2px;
      }

      .dd-check {
        width: 1rem;
        height: 1rem;
        flex-shrink: 0;
        color: var(--accent);
      }

      .dd-option-label {
        flex: 1;
      }

      /* Optional two-tone theme swatch — a small rounded pill split down the
         middle (left = background half, right = accent half), matching the
         mobile settings sheet theme chips. Purely decorative (aria-hidden). */
      .dd-swatch {
        position: relative;
        display: inline-block;
        width: 1rem;
        height: 1rem;
        border-radius: 999px;
        overflow: hidden;
        border: 1px solid var(--border);
        flex: none;
      }

      .dd-swatch .dd-dot {
        position: absolute;
        inset: 0;
        display: block;
      }

      .dd-swatch .dd-dot-accent {
        left: 50%;
      }
    `,
  ],
})
export class HeaderDropdownComponent {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  /** Selectable options, in display order. */
  @Input({ required: true }) options: DropdownOption[] = [];
  /** Currently selected value (controlled by the host). */
  @Input() value: string | null = null;
  /** Accessible name for the trigger and listbox. */
  @Input() ariaLabel = '';
  /**
   * Open the menu ABOVE the trigger instead of below. Used inside the mobile
   * settings bottom-sheet, where a downward menu would run off the bottom of
   * the screen. Top-bar header dropdowns keep the default (open downward).
   */
  @Input() dropUp = false;

  /** Emitted with the chosen option's value. */
  @Output() valueChange = new EventEmitter<string>();

  @ViewChild('trigger') private trigger?: ElementRef<HTMLButtonElement>;
  @ViewChildren('option') private optionEls?: QueryList<ElementRef<HTMLLIElement>>;

  readonly open = signal(false);
  readonly activeIndex = signal(-1);

  /**
   * Label of the selected option (falls back to the raw value).
   *
   * A plain getter — NOT a `computed()` — so it re-evaluates on every change
   * detection pass and therefore tracks the `value`/`options` @Input properties
   * (which are plain properties, not signals, and so are invisible to
   * `computed`). This mirrors how the option rows read `opt.label` directly.
   */
  get selectedLabel(): string {
    const v = this.value;
    return this.options.find((o) => o.value === v)?.label ?? v ?? '';
  }

  /**
   * Two-tone swatch of the selected option, if it carries one (else null).
   *
   * Also a plain getter (see {@link selectedLabel}) so the trigger swatch
   * reactively reflects the CURRENT `value` — a `computed()` here would stay
   * pinned to the first-rendered theme because @Input changes don't notify it.
   */
  get selectedSwatch(): [string, string] | null {
    const v = this.value;
    return this.options.find((o) => o.value === v)?.swatch ?? null;
  }

  toggle(): void {
    this.open() ? this.close() : this.openMenu();
  }

  private openMenu(): void {
    const selected = this.options.findIndex((o) => o.value === this.value);
    this.activeIndex.set(selected >= 0 ? selected : 0);
    this.open.set(true);
    // Focus the active option once the listbox has rendered.
    setTimeout(() => this.focusActive(), 0);
  }

  private close(returnFocus = false): void {
    if (!this.open()) return;
    this.open.set(false);
    if (returnFocus) {
      this.trigger?.nativeElement.focus();
    }
  }

  select(opt: DropdownOption): void {
    if (opt.value !== this.value) {
      this.valueChange.emit(opt.value);
    }
    this.close(true);
  }

  onTriggerKeydown(event: KeyboardEvent): void {
    switch (event.key) {
      case 'ArrowDown':
      case 'ArrowUp':
      case 'Enter':
      case ' ':
      case 'Spacebar':
        event.preventDefault();
        if (!this.open()) this.openMenu();
        break;
      case 'Escape':
        if (this.open()) {
          event.preventDefault();
          this.close(true);
        }
        break;
    }
  }

  onListKeydown(event: KeyboardEvent): void {
    const last = this.options.length - 1;
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        this.move(Math.min(last, this.activeIndex() + 1));
        break;
      case 'ArrowUp':
        event.preventDefault();
        this.move(Math.max(0, this.activeIndex() - 1));
        break;
      case 'Home':
        event.preventDefault();
        this.move(0);
        break;
      case 'End':
        event.preventDefault();
        this.move(last);
        break;
      case 'Enter':
      case ' ':
      case 'Spacebar': {
        event.preventDefault();
        const opt = this.options[this.activeIndex()];
        if (opt) this.select(opt);
        break;
      }
      case 'Escape':
        event.preventDefault();
        this.close(true);
        break;
      case 'Tab':
        // Let focus leave naturally, but collapse the menu.
        this.close();
        break;
    }
  }

  private move(index: number): void {
    this.activeIndex.set(index);
    this.focusActive();
  }

  private focusActive(): void {
    const el = this.optionEls?.get(this.activeIndex());
    el?.nativeElement.focus();
  }

  /** Close when a click lands outside this component. */
  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent): void {
    if (!this.open()) return;
    if (!this.host.nativeElement.contains(event.target as Node)) {
      this.close();
    }
  }
}
