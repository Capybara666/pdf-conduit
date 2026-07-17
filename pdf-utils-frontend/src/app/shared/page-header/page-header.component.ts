import { Component, Input } from '@angular/core';

/** Shared operation-page heading: title + one-line description. */
@Component({
  selector: 'app-page-header',
  standalone: true,
  template: `
    <header class="op-head">
      <h1>{{ title }}</h1>
      @if (description) {
        <p class="desc">{{ description }}</p>
      }
    </header>
  `,
})
export class PageHeaderComponent {
  @Input({ required: true }) title = '';
  @Input() description = '';
}
