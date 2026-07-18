import { Component, Input } from '@angular/core';
import { TranslocoModule } from '@jsverse/transloco';

/**
 * Small accessible loading spinner with an optional label. Pure CSS, no assets.
 */
@Component({
  selector: 'app-spinner',
  standalone: true,
  imports: [TranslocoModule],
  template: `
    <div class="spinner" role="status" [attr.aria-label]="label || ('common.loading' | transloco)">
      <span class="ring" aria-hidden="true"></span>
      @if (label) {
        <span class="label">{{ label }}</span>
      }
    </div>
  `,
  styles: [
    `
      .spinner {
        display: inline-flex;
        align-items: center;
        gap: 0.6rem;
        color: var(--text-muted);
        font-size: 0.9rem;
      }
      .ring {
        width: 1.25rem;
        height: 1.25rem;
        border: 3px solid var(--border);
        border-top-color: var(--accent);
        border-radius: 50%;
        animation: spin 0.7s linear infinite;
      }
      @keyframes spin {
        to {
          transform: rotate(360deg);
        }
      }
      @media (prefers-reduced-motion: reduce) {
        .ring {
          animation-duration: 2s;
        }
      }
    `,
  ],
})
export class SpinnerComponent {
  @Input() label = '';
}
