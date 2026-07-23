import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { TranslocoModule } from '@jsverse/transloco';

import { ApiService } from '../../core/api.service';
import { formatBytes } from '../../core/download.util';
import { OperationState } from '../../core/operation-state';
import { WorkStateService } from '../../core/work-state.service';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
import { ResultPanelComponent } from '../../shared/result-panel/result-panel.component';

/** Target-size unit choices offered by the picker. */
type TargetUnit = 'KB' | 'MB' | 'GB';
const UNIT_BYTES: Record<TargetUnit, number> = { KB: 1024, MB: 1024 ** 2, GB: 1024 ** 3 };

/** Compress one or more PDFs toward a target size (e.g. "5MB"). */
@Component({
  selector: 'app-compress-page',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    TranslocoModule,
    FileDropZoneComponent,
    PageHeaderComponent,
    ResultPanelComponent,
  ],
  template: `
    <section class="op-page">
      <app-page-header
        [title]="'pages.compress.title' | transloco"
        [description]="'pages.compress.description' | transloco"
      />

      <app-file-drop-zone
        [multiple]="true"
        accept=".pdf"
        [hint]="'pages.compress.hint' | transloco"
        (filesChange)="files.set($event)"
      />

      @if (files().length) {
        <ul class="file-sizes card">
          @for (f of files(); track f) {
            <li>
              <span class="name" [title]="f.name">{{ f.name }}</span>
              <span class="size">{{ formatBytes(f.size) }}</span>
              @if (isNoop(f.size)) {
                <span class="warn" role="note">{{ 'pages.compress.noopWarning' | transloco }}</span>
              }
            </li>
          }
        </ul>
      }

      <div class="card form-grid">
        <div class="field">
          <label for="cp-amount">{{ 'pages.compress.target' | transloco }}</label>
          <div class="target-row">
            <input
              id="cp-amount"
              type="number"
              min="0"
              step="any"
              inputmode="decimal"
              [formControl]="targetAmount"
              [placeholder]="'pages.compress.targetPlaceholder' | transloco"
            />
            <select
              id="cp-unit"
              class="unit"
              [formControl]="targetUnit"
              [attr.aria-label]="'pages.compress.unitLabel' | transloco"
            >
              <option value="KB">KB</option>
              <option value="MB">MB</option>
              <option value="GB">GB</option>
            </select>
          </div>
          <span class="help">
            {{ 'pages.compress.targetHelp1' | transloco }}
            <code>KB</code>/<code>MB</code>
            {{ 'pages.compress.targetHelp2' | transloco }}
          </span>
          @if (targetAmount.invalid && targetAmount.touched) {
            <span class="err">{{ 'pages.compress.amountError' | transloco }}</span>
          } @else if (isBlankTarget()) {
            <span class="err">{{ 'pages.compress.blankWarning' | transloco }}</span>
          } @else if (allNoop()) {
            <span class="err">{{ 'pages.compress.allNoopWarning' | transloco }}</span>
          }
        </div>

        <div class="field">
          <label for="cp-dpi">{{ 'pages.compress.dpi' | transloco }}</label>
          <select id="cp-dpi" [formControl]="dpi">
            <option value="none">{{ 'pages.compress.dpiNone' | transloco }}</option>
            <option value="screen">{{ 'pages.compress.dpiScreen' | transloco }}</option>
            <option value="ebook">{{ 'pages.compress.dpiEbook' | transloco }}</option>
            <option value="print">{{ 'pages.compress.dpiPrint' | transloco }}</option>
          </select>
          <span class="help">{{ 'pages.compress.dpiHelp' | transloco }}</span>
        </div>

        <div class="field">
          <label class="check">
            <input type="checkbox" [formControl]="grayscale" />
            {{ 'pages.compress.grayscale' | transloco }}
          </label>
          <span class="help">{{ 'pages.compress.grayscaleHelp' | transloco }}</span>
        </div>
      </div>

      <div class="btn-row">
        <button
          type="button"
          class="btn btn-primary"
          [disabled]="!files().length || targetAmount.invalid || state.loading()"
          (click)="submit()"
        >
          {{ 'pages.compress.submit' | transloco }}
          @if (files().length) {
            · {{ 'common.fileCount' | transloco: { count: files().length } }}
          }
        </button>
        <button type="button" class="btn" (click)="clear()">{{ 'common.clear' | transloco }}</button>
      </div>

      <app-result-panel
        [loading]="state.loading()"
        [loadingLabel]="'pages.compress.loading' | transloco"
        [error]="state.error()"
        [result]="state.result()"
        (retry)="submit()"
      />

      @if (floorNotice(); as n) {
        <p class="floor-note card" role="note">
          {{ 'pages.compress.floorNotice' | transloco: { floor: n.floor, target: n.target } }}
        </p>
      }
    </section>
  `,
  styles: [
    `
      .file-sizes {
        list-style: none;
        margin: 0 0 1rem;
        padding: 0.5rem 0.75rem;
        display: flex;
        flex-direction: column;
        gap: 0.35rem;
      }
      .file-sizes li {
        display: flex;
        align-items: center;
        gap: 0.6rem;
        font-size: 0.88rem;
      }
      .file-sizes .name {
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .file-sizes .size {
        margin-left: auto;
        color: var(--text-muted);
        font-variant-numeric: tabular-nums;
        white-space: nowrap;
      }
      .file-sizes .warn {
        color: var(--danger);
        font-size: 0.8rem;
        white-space: nowrap;
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
      .floor-note {
        margin-top: 1rem;
        padding: 0.75rem 1rem;
        font-size: 0.9rem;
        line-height: 1.5;
        color: var(--text-muted);
        border-left: 3px solid var(--danger);
      }
    `,
  ],
})
export class CompressPage {
  protected readonly files = signal<File[]>([]);
  // The user types a positive NUMBER and picks a unit; the two compose the "5MB"-style string
  // the backend's `targetSize` parser expects (see composeTarget()).
  protected readonly targetAmount = new FormControl<number | null>(5, {
    validators: [Validators.required, Validators.min(0.0000001)],
  });
  /** Unit for the numeric amount; MB is the sensible default. */
  protected readonly targetUnit = new FormControl<TargetUnit>('MB', { nonNullable: true });
  /** Optional image-resolution ceiling: 'none' (default), 'screen', 'ebook', 'print'. */
  protected readonly dpi = new FormControl<'none' | 'screen' | 'ebook' | 'print'>('none', {
    nonNullable: true,
  });
  /** Re-encode images as grayscale for extra savings. */
  protected readonly grayscale = new FormControl(false, { nonNullable: true });
  protected readonly state = new OperationState();

  /** Live amount/unit as signals so warnings react to typing and unit changes. */
  private readonly amountValue = toSignal(this.targetAmount.valueChanges, {
    initialValue: this.targetAmount.value,
  });
  private readonly unitValue = toSignal(this.targetUnit.valueChanges, {
    initialValue: this.targetUnit.value,
  });
  /** Parsed target in bytes, or null when blank/non-positive. */
  private readonly targetBytes = computed(() => {
    const amount = this.amountValue();
    if (amount == null || !(amount > 0)) return null;
    return amount * UNIT_BYTES[this.unitValue()];
  });
  protected readonly isBlankTarget = computed(() => this.amountValue() == null);
  /** True when every selected file is already at/below the target (a no-op). */
  protected readonly allNoop = computed(() => {
    const fs = this.files();
    return fs.length > 0 && fs.every((f) => this.isNoop(f.size));
  });

  protected readonly formatBytes = formatBytes;

  /** The target that produced the current result (so the notice always matches it). */
  private readonly lastTarget = signal<string | null>(null);

  /**
   * When the last run could not reach the target, a friendly heads-up: the smallest size that
   * was actually achievable for this file, alongside the target the user asked for.
   */
  protected readonly floorNotice = computed(() => {
    const c = this.state.result()?.compression;
    if (!c || c.targetReached !== false) return null;
    const floorBytes = c.estimatedFloorBytes ?? c.resultBytes;
    const target = this.lastTarget();
    if (floorBytes == null || !target) return null;
    return { floor: formatBytes(floorBytes), target };
  });

  private readonly workState = inject(WorkStateService);

  constructor(private readonly api: ApiService) {
    this.workState.persist('compress', {
      targetAmount: this.targetAmount,
      targetUnit: this.targetUnit,
      dpi: this.dpi,
      grayscale: this.grayscale,
    });
  }

  clear(): void {
    this.workState.reset('compress');
    this.files.set([]);
    this.state.reset();
  }

  /** A compress target at/above the file's current size cannot shrink it. */
  protected isNoop(size: number): boolean {
    const target = this.targetBytes();
    return target !== null && target >= size;
  }

  /** Compose the backend `targetSize` string from the numeric amount + unit (e.g. 5 + MB → "5MB"). */
  private composeTarget(): string {
    return `${this.targetAmount.value}${this.targetUnit.value}`;
  }

  submit(): void {
    if (!this.files().length || this.targetAmount.invalid) return;
    const target = this.composeTarget();
    this.lastTarget.set(target);
    const fd = new FormData();
    for (const f of this.files()) fd.append('files', f, f.name);
    fd.append('targetSize', target);
    if (this.dpi.value !== 'none') fd.append('dpi', this.dpi.value);
    if (this.grayscale.value) fd.append('grayscale', 'true');
    this.state.run(this.api.compress(fd));
  }
}
