import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoModule } from '@jsverse/transloco';

import { ToastService } from '../../core/toast.service';

/**
 * Renders the global toast queue in a fixed corner region. Mounted once in the
 * app shell. Toasts are polite live-region announcements and can carry a single
 * router-link action (e.g. "See Pro plans").
 */
@Component({
  selector: 'app-toast-container',
  standalone: true,
  imports: [RouterLink, TranslocoModule],
  template: `
    <div class="toast-region" aria-live="polite" aria-atomic="false">
      @for (t of toasts.toasts(); track t.id) {
        <div class="toast" [attr.data-kind]="t.kind" role="status">
          <span class="glyph" aria-hidden="true">{{ glyph(t.kind) }}</span>
          <div class="body">
            <strong class="title">{{ t.title }}</strong>
            @if (t.message) {
              <p class="msg">{{ t.message }}</p>
            }
            @if (t.action; as a) {
              <a
                class="action"
                [routerLink]="a.link ?? ['/']"
                [fragment]="a.fragment"
                (click)="toasts.dismiss(t.id)"
                >{{ a.label }}</a
              >
            }
          </div>
          <button
            type="button"
            class="close"
            (click)="toasts.dismiss(t.id)"
            [attr.aria-label]="'toast.dismiss' | transloco"
          >
            ✕
          </button>
        </div>
      }
    </div>
  `,
  styles: [
    `
      .toast-region {
        position: fixed;
        z-index: 1000;
        right: 1rem;
        bottom: 1rem;
        display: flex;
        flex-direction: column;
        gap: 0.6rem;
        width: min(360px, calc(100vw - 2rem));
        pointer-events: none;
      }
      .toast {
        pointer-events: auto;
        display: flex;
        align-items: flex-start;
        gap: 0.65rem;
        padding: 0.8rem 0.9rem;
        border-radius: var(--radius);
        background: var(--surface);
        border: 1px solid var(--border);
        border-left: 4px solid var(--accent);
        box-shadow: 0 8px 24px rgba(20, 30, 45, 0.16);
        animation: toast-in 0.18s ease-out;
      }
      .toast[data-kind='success'] {
        border-left-color: var(--success);
      }
      .toast[data-kind='warning'] {
        border-left-color: var(--warning);
      }
      .toast[data-kind='error'] {
        border-left-color: var(--danger);
      }
      .glyph {
        flex-shrink: 0;
        font-size: 1rem;
        line-height: 1.4;
      }
      .body {
        flex: 1;
        min-width: 0;
      }
      .title {
        display: block;
        font-size: 0.92rem;
      }
      .msg {
        margin: 0.2rem 0 0;
        font-size: 0.82rem;
        color: var(--text-muted);
      }
      .action {
        display: inline-block;
        margin-top: 0.5rem;
        font-size: 0.82rem;
        font-weight: 600;
        color: var(--accent);
        text-decoration: none;
      }
      .action:hover {
        text-decoration: underline;
      }
      .close {
        flex-shrink: 0;
        border: none;
        background: transparent;
        color: var(--text-muted);
        cursor: pointer;
        font-size: 0.85rem;
        padding: 0.1rem 0.25rem;
        border-radius: 4px;
      }
      .close:hover {
        color: var(--text);
      }
      @keyframes toast-in {
        from {
          opacity: 0;
          transform: translateY(8px);
        }
      }
      @media (prefers-reduced-motion: reduce) {
        .toast {
          animation: none;
        }
      }
    `,
  ],
})
export class ToastContainerComponent {
  protected readonly toasts = inject(ToastService);

  protected glyph(kind: string): string {
    switch (kind) {
      case 'success':
        return '✓';
      case 'warning':
        return '!';
      case 'error':
        return '⚠';
      default:
        return 'ℹ';
    }
  }
}
