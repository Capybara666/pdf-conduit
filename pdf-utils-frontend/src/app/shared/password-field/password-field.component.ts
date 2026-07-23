import { Component, Input, forwardRef, signal } from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';

/**
 * Reusable password input with an eye-icon reveal toggle rendered INSIDE the
 * field (right-aligned, never overlapping the typed text). Implements
 * ControlValueAccessor so it drops in wherever a plain `<input type="password">`
 * bound to reactive forms would go — works with both `formControlName`
 * (inside a FormGroup) and a standalone `[formControl]`.
 *
 * Accessibility: the toggle is a real <button> with a dynamic aria-label
 * ("Show password" / "Hide password") and aria-pressed reflecting the current
 * reveal state; the eye SVG is decorative (aria-hidden).
 */
@Component({
  selector: 'app-password-field',
  standalone: true,
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => PasswordFieldComponent),
      multi: true,
    },
  ],
  template: `
    <div class="pw-field">
      <input
        class="pw-input"
        [id]="inputId"
        [type]="revealed() ? 'text' : 'password'"
        [attr.autocomplete]="autocomplete"
        [value]="value()"
        [disabled]="disabled()"
        (input)="onInput($event)"
        (blur)="onTouched()"
      />
      <button
        type="button"
        class="pw-toggle"
        tabindex="0"
        [attr.aria-pressed]="revealed()"
        [attr.aria-label]="ariaLabel()"
        [disabled]="disabled()"
        (click)="toggle()"
      >
        @if (revealed()) {
          <!-- eye-off -->
          <svg
            viewBox="0 0 24 24"
            width="20"
            height="20"
            fill="none"
            stroke="currentColor"
            stroke-width="1.8"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <path d="M9.9 5.2A9.8 9.8 0 0 1 12 5c6.5 0 10 7 10 7a17.6 17.6 0 0 1-3.2 4.1" />
            <path d="M6.1 6.1A17.4 17.4 0 0 0 2 12s3.5 7 10 7a9.7 9.7 0 0 0 4-.9" />
            <path d="M9.9 9.9a3 3 0 0 0 4.2 4.2" />
            <line x1="3" y1="3" x2="21" y2="21" />
          </svg>
        } @else {
          <!-- eye -->
          <svg
            viewBox="0 0 24 24"
            width="20"
            height="20"
            fill="none"
            stroke="currentColor"
            stroke-width="1.8"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7Z" />
            <circle cx="12" cy="12" r="3" />
          </svg>
        }
      </button>
    </div>
  `,
  styles: [
    `
      .pw-field {
        position: relative;
        display: block;
      }
      .pw-input {
        /* keep room on the right so the reveal icon never sits on typed text */
        padding: 0.5rem 2.6rem 0.5rem 0.65rem;
      }
      .pw-toggle {
        position: absolute;
        top: 50%;
        right: 0.3rem;
        transform: translateY(-50%);
        display: inline-flex;
        align-items: center;
        justify-content: center;
        width: 2rem;
        height: 2rem;
        padding: 0;
        border: none;
        background: transparent;
        color: var(--text-muted);
        border-radius: calc(var(--radius) - 8px);
        cursor: pointer;
        transition: color var(--dur-fast) var(--ease-standard),
          background var(--dur-fast) var(--ease-standard);
      }
      .pw-toggle:hover:not(:disabled) {
        color: var(--text);
        background: var(--surface);
      }
      .pw-toggle:focus-visible {
        outline: 2px solid var(--accent);
        outline-offset: 1px;
      }
      .pw-toggle:disabled {
        cursor: default;
        opacity: 0.5;
      }
    `,
  ],
})
export class PasswordFieldComponent implements ControlValueAccessor {
  /** Id applied to the inner <input> so an external <label for> can target it. */
  @Input() inputId = '';
  /** Forwarded to the input's autocomplete attribute (e.g. 'new-password', 'off'). */
  @Input() autocomplete = 'off';

  protected readonly value = signal('');
  protected readonly disabled = signal(false);
  protected readonly revealed = signal(false);

  private onChange: (value: string) => void = () => {};
  protected onTouched: () => void = () => {};

  // TODO(i18n): these aria-labels are plain English pending central localization.
  // Desired keys: common.showPassword ("Show password") / common.hidePassword ("Hide password").
  protected ariaLabel(): string {
    return this.revealed() ? 'Hide password' : 'Show password';
  }

  toggle(): void {
    this.revealed.update((v) => !v);
  }

  protected onInput(event: Event): void {
    const next = (event.target as HTMLInputElement).value;
    this.value.set(next);
    this.onChange(next);
  }

  // ControlValueAccessor
  writeValue(value: string | null): void {
    this.value.set(value ?? '');
  }
  registerOnChange(fn: (value: string) => void): void {
    this.onChange = fn;
  }
  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }
  setDisabledState(isDisabled: boolean): void {
    this.disabled.set(isDisabled);
  }
}
