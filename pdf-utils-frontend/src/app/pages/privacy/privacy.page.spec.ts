import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Translation, TranslocoService } from '@jsverse/transloco';

import ko from '../../../../public/i18n/ko.json';
import pl from '../../../../public/i18n/pl.json';
import { PrivacyPage } from './privacy.page';
import { TRANSLOCO_TESTING_PROVIDERS, translocoTesting } from '../../testing/transloco-testing';

/**
 * The free-tier paragraph is ONE key with `{linkStart}`/`{linkEnd}` arguments,
 * so the whole sentence (including the link label) is translated as a unit.
 * Assembling it from three keys glued around an `<a>` would pin every language
 * to English word order, which comes out ungrammatical in Polish and Korean.
 * These specs assert the assembled text, not just the fragments: a regression
 * that drops a part or injects stray whitespace shows up here.
 */
describe('PrivacyPage', () => {
  function setup(lang: string) {
    TestBed.configureTestingModule({
      imports: [
        PrivacyPage,
        translocoTesting({
          langs: { pl: pl as Translation, ko: ko as Translation },
        }),
      ],
      providers: [TRANSLOCO_TESTING_PROVIDERS, provideRouter([])],
    });
    TestBed.inject(TranslocoService).setActiveLang(lang);
    const fixture = TestBed.createComponent(PrivacyPage);
    fixture.detectChanges();
    return fixture;
  }

  it('renders the free-tier sentence as one grammatical unit around the link (pl)', () => {
    const fixture = setup('pl');
    const paragraph: HTMLParagraphElement = Array.from(
      fixture.nativeElement.querySelectorAll('p'),
    ).find((p) => (p as HTMLElement).querySelector('a[href="/#pro"]')) as HTMLParagraphElement;

    expect(paragraph).withContext('paragraph carrying the Pro-plans link').toBeTruthy();
    const link = paragraph.querySelector('a')!;
    expect(link.textContent).toBe('planów Pro');
    // No sentinel leaks, and the surrounding words keep their own spacing.
    expect(paragraph.textContent).toContain('Zobacz zapowiedź planów Pro na stronie głównej.');
    expect(paragraph.textContent).not.toContain('\u0001');
    expect(paragraph.textContent).not.toContain('\u0002');
  });

  it('lets a space-free language close up around the link (ko)', () => {
    const fixture = setup('ko');
    const paragraph: HTMLParagraphElement = Array.from(
      fixture.nativeElement.querySelectorAll('p'),
    ).find((p) => (p as HTMLElement).querySelector('a[href="/#pro"]')) as HTMLParagraphElement;

    expect(paragraph.querySelector('a')!.textContent).toBe('Pro 플랜');
    expect(paragraph.textContent).toContain('홈페이지의 Pro 플랜 안내를 참고하세요.');
  });
});
