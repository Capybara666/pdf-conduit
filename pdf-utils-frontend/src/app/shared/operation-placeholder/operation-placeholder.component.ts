import { Component, Input } from '@angular/core';

import { NavItem } from '../../core/operations';

/**
 * Placeholder body shown by every operation page until a later agent implements
 * the real form. Renders the operation title, description and a "coming soon"
 * note. Operation pages pass their `NavItem`.
 */
@Component({
  selector: 'app-operation-placeholder',
  standalone: true,
  template: `
    <section class="page">
      <header class="page-head">
        <h1>{{ item.label }}</h1>
        <p class="desc">{{ item.description }}</p>
      </header>
      <div class="stub">
        <p><strong>{{ item.label }}</strong> isn't wired up yet.</p>
        <p class="muted">
          This is a scaffold placeholder. A later agent implements the form here
          — file drop-zone, options, and a call to
          <code>ApiService.runOperation('{{ item.id }}', …)</code>.
        </p>
      </div>
    </section>
  `,
  styles: [
    `
      .page {
        max-width: 760px;
      }
      .page-head {
        margin-bottom: 1.5rem;
      }
      h1 {
        margin: 0 0 0.35rem;
        font-size: 1.6rem;
      }
      .desc {
        margin: 0;
        color: var(--text-muted);
      }
      .stub {
        border: 1px dashed var(--border-strong);
        border-radius: var(--radius);
        background: var(--surface-2);
        padding: 2rem;
      }
      .stub p {
        margin: 0 0 0.5rem;
      }
      .muted {
        color: var(--text-muted);
        font-size: 0.9rem;
      }
      code {
        background: var(--surface);
        border: 1px solid var(--border);
        padding: 0.1rem 0.35rem;
        border-radius: 4px;
      }
    `,
  ],
})
export class OperationPlaceholderComponent {
  @Input({ required: true }) item!: NavItem;
}
