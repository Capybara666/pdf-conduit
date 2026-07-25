import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';

import { PipelineInspectorComponent } from './pipeline-inspector.component';
import { ApiService } from '../../../core/api.service';
import { MIN_RENDER_DPI } from '../../../core/capabilities.service';
import { newCanvasNode } from '../../../core/pipeline.models';
import { environment } from '../../../../environments/environment';
import {
  TRANSLOCO_TESTING_PROVIDERS,
  translocoTesting,
} from '../../../testing/transloco-testing';

/**
 * `/api/pipeline/run` enforces exactly the same `render.max-dpi` as
 * `/api/to-images` (via `PipelineLimitsGuard.checkRender`), so the TO_IMAGES
 * node's DPI field must quote the same advertised ceiling as the To Images page.
 * It used to hard-code `max="600"` independently — two places to forget.
 */
describe('PipelineInspectorComponent — TO_IMAGES DPI ceiling', () => {
  let fixture: ComponentFixture<PipelineInspectorComponent>;

  const baseCaps = {
    officeEnabled: true,
    ocrEnabled: false,
    ocrLanguages: [] as string[],
    maxFileSizeBytes: 25 * 1024 * 1024,
    maxFilesPerRequest: 15,
  };

  function build(caps: unknown, fail = false): void {
    TestBed.configureTestingModule({
      imports: [PipelineInspectorComponent, translocoTesting()],
      providers: [
        TRANSLOCO_TESTING_PROVIDERS,
        {
          provide: ApiService,
          useValue: {
            getOperations: () => of([]),
            getCapabilities: () => (fail ? throwError(() => new Error('offline')) : of(caps)),
          },
        },
      ],
    });
    fixture = TestBed.createComponent(PipelineInspectorComponent);
    fixture.componentInstance.node = newCanvasNode('n1', 'TO_IMAGES', 0, 0);
    fixture.detectChanges();
  }

  afterEach(() => TestBed.resetTestingModule());

  /** The DPI number input — the only `type="number"` field a TO_IMAGES node renders. */
  function dpiInput(): HTMLInputElement {
    return fixture.nativeElement.querySelector('input[type="number"]') as HTMLInputElement;
  }

  function helpText(): string {
    return (fixture.nativeElement.querySelector('.help') as HTMLElement).textContent!.trim();
  }

  it('caps the field at the advertised maxDpi and says so', () => {
    build({ ...baseCaps, maxDpi: 300 });

    expect(dpiInput().max).toBe('300');
    expect(dpiInput().min).toBe(String(MIN_RENDER_DPI));
    expect(helpText()).toContain('300');
    expect(helpText()).not.toContain('600');
  });

  it('falls back to the hard-coded value when the field is absent', () => {
    build(baseCaps);

    expect(dpiInput().max).toBe(String(environment.maxDpi));
    expect(helpText()).toContain(String(environment.maxDpi));
  });

  it('falls back to the hard-coded value when the capabilities call fails', () => {
    build({ ...baseCaps, maxDpi: 300 }, true);

    expect(dpiInput().max).toBe(String(environment.maxDpi));
  });

  it('never leaves the field unbounded', () => {
    build({ ...baseCaps, maxDpi: 0 });

    expect(dpiInput().max).toBeTruthy();
    expect(Number(dpiInput().max)).toBe(environment.maxDpi);
  });
});
