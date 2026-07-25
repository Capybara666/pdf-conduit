import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';

import { PageHeaderComponent } from '../../shared/page-header/page-header.component';

/**
 * Sentinels handed to the `{linkStart}` / `{linkEnd}` arguments of
 * `privacy.limitsBody`, so the rendered sentence can be split back into
 * before / link-label / after. Control characters: they can never occur in
 * translated copy, so the split is unambiguous.
 */
const LINK_START = '\u0001';
const LINK_END = '\u0002';

/**
 * Plain, honest privacy page. States the in-memory processing model, that
 * nothing is stored, and the free-tier limits. No third-party content.
 */
@Component({
  selector: 'app-privacy-page',
  standalone: true,
  imports: [PageHeaderComponent, RouterLink, TranslocoModule],
  template: `
    <section class="op-page prose-page">
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

        <!-- TODO(user): replace "operator of the PDF Conduit service" in the
             i18n controllerBody strings with the legal name before launch -->
        <h2>{{ 'privacy.controllerTitle' | transloco }}</h2>
        <p>{{ 'privacy.controllerBody' | transloco }}</p>

        <h2>{{ 'privacy.limitsTitle' | transloco }}</h2>
        <!-- One sentence, one key: the link is spliced INTO privacy.limitsBody
             (see the "limits" computed below), so every language keeps control
             of word order, case and the link label itself. No whitespace around
             the anchor — the spacing lives inside the translated string, which
             is the only way CJK (no spaces) and European copy both come out
             right. -->
        <p>
          {{ limits().before
          }}<a routerLink="/" fragment="pro">{{ limits().label }}</a
          >{{ limits().after }}
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
      /* NO max-width here: the reading measure is owned by the shared
         .op-page.prose-page container (styles.scss), which stays centred in
         both width modes. Capping this card instead left it flush against the
         left edge in wide mode. */
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
export class PrivacyPage {
  private readonly transloco = inject(TranslocoService);

  /**
   * The free-tier paragraph rendered with the link placeholders replaced by
   * {@link LINK_START} / {@link LINK_END}. `selectTranslate` (not the
   * synchronous `translate`) so the paragraph re-renders once the dictionary
   * loads and on every language switch.
   */
  private readonly limitsText = toSignal(
    this.transloco.selectTranslate<string>('privacy.limitsBody', {
      linkStart: LINK_START,
      linkEnd: LINK_END,
    }),
    { initialValue: '' },
  );

  /**
   * The sentence split around its inline link. This replaced a three-key
   * `body1 + link + body2` assembly, which forced every translator into
   * English word order and produced ungrammatical Polish and Korean.
   */
  protected readonly limits = computed(() => {
    const text = this.limitsText();
    const start = text.indexOf(LINK_START);
    const end = text.indexOf(LINK_END, start + 1);
    if (start < 0 || end < 0) {
      // Malformed translation: show the sentence rather than nothing.
      return { before: text, label: '', after: '' };
    }
    return {
      before: text.slice(0, start),
      label: text.slice(start + LINK_START.length, end),
      after: text.slice(end + LINK_END.length),
    };
  });
}
