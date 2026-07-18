import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoModule } from '@jsverse/transloco';

import { PageHeaderComponent } from '../../shared/page-header/page-header.component';

/**
 * Plain, honest privacy page. States the in-memory processing model, that
 * nothing is stored, and the free-tier limits. No third-party content.
 */
@Component({
  selector: 'app-privacy-page',
  standalone: true,
  imports: [PageHeaderComponent, RouterLink, TranslocoModule],
  template: `
    <section class="op-page">
      <app-page-header
        [title]="'privacy.title' | transloco"
        [description]="'privacy.description' | transloco"
      />

      <div class="card prose">
        <h2>{{ 'privacy.memoryTitle' | transloco }}</h2>
        <p>{{ 'privacy.memoryBody' | transloco }}</p>

        <h2>{{ 'privacy.exceptionTitle' | transloco }}</h2>
        <p>{{ 'privacy.exceptionBody' | transloco }}</p>

        <h2>{{ 'privacy.accountTitle' | transloco }}</h2>
        <p>{{ 'privacy.accountBody' | transloco }}</p>

        <h2>{{ 'privacy.limitsTitle' | transloco }}</h2>
        <p>
          {{ 'privacy.limitsBody1' | transloco }}
          <a routerLink="/" fragment="pro">{{ 'privacy.limitsLink' | transloco }}</a>
          {{ 'privacy.limitsBody2' | transloco }}
        </p>

        <h2>{{ 'privacy.questionsTitle' | transloco }}</h2>
        <p>{{ 'privacy.questionsBody' | transloco }}</p>

        <p>
          <a class="btn" routerLink="/">{{ 'privacy.backHome' | transloco }}</a>
        </p>
      </div>
    </section>
  `,
  styles: [
    `
      .prose {
        max-width: 680px;
      }
      .prose h2 {
        font-size: 1.15rem;
        margin: 1.5rem 0 0.5rem;
      }
      .prose h2:first-child {
        margin-top: 0;
      }
      .prose p {
        margin: 0 0 0.75rem;
        color: var(--text);
      }
      .prose code {
        background: var(--surface-2);
        padding: 0.1rem 0.35rem;
        border-radius: 4px;
      }
      .btn {
        text-decoration: none;
        display: inline-flex;
      }
    `,
  ],
})
export class PrivacyPage {}
