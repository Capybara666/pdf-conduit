import { Component, Input } from '@angular/core';

import { ApiError, RunResult } from '../../core/api.models';
import { downloadRunResult, formatBytes } from '../../core/download.util';
import { SpinnerComponent } from '../spinner/spinner.component';

/**
 * Presentational panel for the outcome of an operation: a loading state, a
 * typed error, or a success with a download button. Operation forms feed it
 * `[loading]`, `[error]` and `[result]`.
 */
@Component({
  selector: 'app-result-panel',
  standalone: true,
  imports: [SpinnerComponent],
  templateUrl: './result-panel.component.html',
  styleUrl: './result-panel.component.scss',
})
export class ResultPanelComponent {
  @Input() loading = false;
  @Input() loadingLabel = 'Processing…';
  @Input() error: ApiError | null = null;
  @Input() result: RunResult | null = null;

  protected readonly formatBytes = formatBytes;

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
