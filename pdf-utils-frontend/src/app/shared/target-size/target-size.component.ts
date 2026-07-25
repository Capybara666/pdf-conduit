import { Component, Input } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { TranslocoModule } from '@jsverse/transloco';

/** Target-size unit choices offered by the picker. */
export type TargetUnit = 'KB' | 'MB' | 'GB';

/** Byte multiplier for each offered unit (binary, matching the backend's parser). */
export const UNIT_BYTES: Record<TargetUnit, number> = { KB: 1024, MB: 1024 ** 2, GB: 1024 ** 3 };

/** Compose the backend `targetSize` string from amount + unit (5 + MB → "5MB"). */
export function composeTargetSize(amount: number | null, unit: TargetUnit): string {
  return `${amount}${unit}`;
}

/**
 * The amount + unit pair that makes up a compression target, as one control.
 *
 * It deliberately owns no state: the caller passes its own FormControls, so
 * whatever persistence, validation and derived signals a page already has keep
 * working unchanged — this component only renders and binds them.
 */
@Component({
  selector: 'app-target-size',
  standalone: true,
  imports: [ReactiveFormsModule, TranslocoModule],
  template: `
    <div class="target-row">
      <input
        type="number"
        min="0"
        step="any"
        inputmode="decimal"
        [id]="inputId"
        [formControl]="amount"
        [placeholder]="placeholder"
      />
      <select class="unit" [formControl]="unit" [attr.aria-label]="'common.unit' | transloco">
        <option value="KB">KB</option>
        <option value="MB">MB</option>
        <option value="GB">GB</option>
      </select>
    </div>
  `,
  styles: [
    `
      /* Fills its slot in any layout, not just a flex column that blockifies it. */
      :host {
        display: block;
      }
      .target-row {
        display: flex;
        gap: 0.5rem;
        align-items: stretch;
      }
      .target-row input[type='number'] {
        flex: 1 1 auto;
        min-width: 0;
      }
      .target-row .unit {
        flex: 0 0 auto;
        width: auto;
      }
    `,
  ],
})
export class TargetSizeComponent {
  @Input({ required: true }) amount!: FormControl<number | null>;
  @Input({ required: true }) unit!: FormControl<TargetUnit>;
  /** id for the number input, so the caller's <label for> keeps working. */
  @Input() inputId = 'target-amount';
  @Input() placeholder = '';
}
