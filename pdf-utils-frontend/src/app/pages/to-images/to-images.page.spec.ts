import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormControl } from '@angular/forms';
import { EMPTY, of, throwError } from 'rxjs';

import { ToImagesPage } from './to-images.page';
import { ApiService } from '../../core/api.service';
import { CapabilitiesService, MIN_RENDER_DPI } from '../../core/capabilities.service';
import { environment } from '../../../environments/environment';
import { TRANSLOCO_TESTING_PROVIDERS, translocoTesting } from '../../testing/transloco-testing';

/**
 * The backend refuses anything above `render.max-dpi` (300 in the strict/public
 * preset), so a hard-coded `max` on the DPI field would call a value valid that
 * the server answers 422 for. The ceiling comes from `GET /api/capabilities`
 * (`maxDpi`), and the four things a user can see or hit — the `max` attribute,
 * the validator, the help text and the error text — must all quote the SAME
 * number. A spec that only checked the validator would miss copy still
 * promising a figure the server will not render.
 */
describe('ToImagesPage — DPI ceiling', () => {
  let fixture: ComponentFixture<ToImagesPage>;

  const baseCaps = {
    officeEnabled: true,
    ocrEnabled: false,
    ocrLanguages: [] as string[],
    maxFileSizeBytes: 25 * 1024 * 1024,
    maxFilesPerRequest: 15,
  };

  /**
   * Builds the page over the REAL `CapabilitiesService` — the point of these
   * specs is the wiring between the advertised number and the form, so stubbing
   * the service would test nothing. `of(caps)` is synchronous, so the ceiling is
   * already in place by the time the component is created (the same ordering as
   * a warm cache); the mutation test below covers the response landing late.
   */
  function build(caps: unknown, fail = false): void {
    TestBed.configureTestingModule({
      imports: [ToImagesPage, translocoTesting()],
      providers: [
        TRANSLOCO_TESTING_PROVIDERS,
        {
          provide: ApiService,
          useValue: {
            getOperations: () => of([]),
            getCapabilities: () => (fail ? throwError(() => new Error('offline')) : of(caps)),
            toImages: () => EMPTY,
          },
        },
      ],
    });
    fixture = TestBed.createComponent(ToImagesPage);
    fixture.detectChanges();
  }

  afterEach(() => TestBed.resetTestingModule());

  /** The `dpi` control (`protected` is a compile-time marker only). */
  function dpiControl(): FormControl<number> {
    return (fixture.componentInstance as unknown as { dpi: FormControl<number> }).dpi;
  }

  function dpiInput(): HTMLInputElement {
    return fixture.nativeElement.querySelector('#ti-dpi') as HTMLInputElement;
  }

  /** The visible helper line under the DPI input. */
  function helpText(): string {
    const field = dpiInput().closest('.field') as HTMLElement;
    return (field.querySelector('.help') as HTMLElement).textContent!.trim();
  }

  /** The inline validation message (only rendered once the control is touched). */
  function errorText(): string {
    const field = dpiInput().closest('.field') as HTMLElement;
    return (field.querySelector('.err') as HTMLElement | null)?.textContent?.trim() ?? '';
  }

  /** Assert every DPI surface quotes `max` — and nothing quotes a stale ceiling. */
  function expectEverythingAgreesOn(max: number): void {
    expect(dpiInput().max).withContext('max attribute').toBe(String(max));
    expect(dpiInput().min).withContext('min attribute').toBe(String(MIN_RENDER_DPI));
    expect(helpText()).withContext('help text').toContain(String(max));
    expect(helpText()).withContext('help text').toContain(String(MIN_RENDER_DPI));

    const dpi = dpiControl();
    dpi.setValue(max);
    expect(dpi.valid).withContext(`${max} must be accepted`).toBeTrue();
    dpi.setValue(max + 1);
    expect(dpi.valid).withContext(`${max + 1} must be refused`).toBeFalse();
    dpi.setValue(MIN_RENDER_DPI - 1);
    expect(dpi.valid).withContext('below the floor must be refused').toBeFalse();
  }

  it('caps the field at the advertised maxDpi, on every surface', () => {
    build({ ...baseCaps, maxDpi: 300 });

    expectEverythingAgreesOn(300);
    // Copy must not promise a range the server does not accept.
    expect(helpText()).not.toContain('600');
  });

  it('shows the advertised ceiling in the error message too', () => {
    build({ ...baseCaps, maxDpi: 300 });

    const dpi = dpiControl();
    dpi.setValue(600);
    dpi.markAsTouched();
    fixture.detectChanges();

    expect(errorText()).toContain('300');
    expect(errorText()).not.toContain('600');
  });

  it('falls back to the hard-coded value when the server omits maxDpi (older backend)', () => {
    build(baseCaps);

    expectEverythingAgreesOn(environment.maxDpi);
  });

  it('falls back to the hard-coded value when the capabilities call fails', () => {
    build({ ...baseCaps, maxDpi: 300 }, true);

    expectEverythingAgreesOn(environment.maxDpi);
  });

  it('never becomes unbounded on a nonsense advertised value', () => {
    for (const nonsense of [0, -1, 12.5, 'lots', null, undefined, NaN]) {
      TestBed.resetTestingModule();
      build({ ...baseCaps, maxDpi: nonsense });

      // Falling open to "no limit" would put us back where we started: a form
      // that green-lights a request the backend answers with 422.
      expect(dpiInput().max).withContext(`maxDpi=${String(nonsense)}`).toBe(
        String(environment.maxDpi),
      );
      expect(Number(dpiInput().max)).toBeGreaterThan(0);
      expect(Number.isFinite(Number(dpiInput().max))).toBeTrue();

      const dpi = dpiControl();
      dpi.setValue(environment.maxDpi + 1);
      expect(dpi.valid).withContext(`maxDpi=${String(nonsense)}`).toBeFalse();
    }
  });

  it('re-judges an already-typed value when the ceiling lands late', () => {
    // Pre-response window: the fallback is in force and 600 looks fine.
    build(baseCaps);
    const dpi = dpiControl();
    dpi.setValue(environment.maxDpi);
    expect(dpi.valid).toBeTrue();

    // The response arrives with a stricter real limit.
    TestBed.inject(CapabilitiesService).maxDpi.set(300);
    fixture.detectChanges();

    expect(dpi.valid).withContext('must be re-validated, not left stale').toBeFalse();
    expectEverythingAgreesOn(300);
  });
});
