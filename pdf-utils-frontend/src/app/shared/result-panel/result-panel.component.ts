import { Component, Input, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';

import { ApiError, RunResult } from '../../core/api.models';
import { downloadRunResult, formatBytes } from '../../core/download.util';
import { errorCopyKeys } from '../../core/error-copy';
import { SpinnerComponent } from '../spinner/spinner.component';

/** Resolved, translated error copy for display in the panel. */
interface ResolvedCopy {
  title: string;
  detail: string;
  hint?: string;
  proLink?: boolean;
}

/**
 * Presentational panel for the outcome of an operation: a loading state, a
 * typed error, or a success with a download button. Operation forms feed it
 * `[loading]`, `[error]` and `[result]`.
 */
@Component({
  selector: 'app-result-panel',
  standalone: true,
  imports: [SpinnerComponent, RouterLink, TranslocoModule],
  templateUrl: './result-panel.component.html',
  styleUrl: './result-panel.component.scss',
})
export class ResultPanelComponent {
  private readonly transloco = inject(TranslocoService);

  @Input() loading = false;
  @Input() loadingLabel = '';
  @Input() error: ApiError | null = null;
  @Input() result: RunResult | null = null;

  protected readonly formatBytes = formatBytes;

  /** Friendly, code-aware, translated presentation copy for the current error. */
  get copy(): ResolvedCopy | null {
    if (!this.error) return null;
    const keys = errorCopyKeys(this.error);
    return {
      title: this.transloco.translate(keys.titleKey),
      detail: keys.detailText || (keys.detailKey ? this.transloco.translate(keys.detailKey) : ''),
      hint: keys.hintKey ? this.transloco.translate(keys.hintKey, keys.hintParams) : undefined,
      proLink: keys.proLink,
    };
  }

  download(): void {
    if (this.result) {
      downloadRunResult(this.result);
    }
  }

  get savedPercent(): number | null {
    const c = this.result?.compression;
    if (!c || !c.originalBytes || c.resultBytes == null) return null;
    return Math.max(0, Math.round((1 - c.resultBytes / c.originalBytes) * 100));
  }
}
