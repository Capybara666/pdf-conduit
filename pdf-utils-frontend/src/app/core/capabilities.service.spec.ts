import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';

import { ApiService } from './api.service';
import { CapabilitiesInfo } from './api.models';
import { CapabilitiesService } from './capabilities.service';
import { environment } from '../../environments/environment';

/**
 * The upload caps are the server's to decide. This service is where the SPA
 * stops hard-coding them: the advertised numbers win when present, and the
 * `environment` constants survive only as the pre-response / offline fallback —
 * a wrong fallback must never be able to outrank a real answer.
 */
describe('CapabilitiesService', () => {
  const full: CapabilitiesInfo = {
    officeEnabled: true,
    ocrEnabled: false,
    ocrLanguages: [],
    maxFileSizeBytes: 25 * 1024 * 1024,
    maxFilesPerRequest: 15,
  };

  /** Builds the service over a stubbed API returning `caps` (or failing). */
  function service(caps: unknown, fail = false): CapabilitiesService {
    TestBed.configureTestingModule({
      providers: [
        {
          provide: ApiService,
          useValue: {
            getOperations: () => of([]),
            getCapabilities: () => (fail ? throwError(() => new Error('offline')) : of(caps)),
          },
        },
      ],
    });
    return TestBed.inject(CapabilitiesService);
  }

  afterEach(() => TestBed.resetTestingModule());

  it('adopts the advertised caps', () => {
    const caps = service({ ...full, maxFileSizeBytes: 40 * 1024 * 1024, maxFilesPerRequest: 9 });

    expect(caps.maxUploadMb()).toBe(40);
    expect(caps.maxFilesPerRequest()).toBe(9);
  });

  it('lets the advertised caps overrule the hard-coded environment values', () => {
    // Stricter than what this build hard-codes: the server's answer must win,
    // otherwise the user waits out a doomed upload for a 413.
    const stricter = environment.maxUploadMb - 1;
    const caps = service({
      ...full,
      maxFileSizeBytes: stricter * 1024 * 1024,
      maxFilesPerRequest: 1,
    });

    expect(caps.maxUploadMb()).toBe(stricter);
    expect(caps.maxFilesPerRequest()).toBe(1);
  });

  it('keeps the environment fallback when the server omits the caps (older backend)', () => {
    const caps = service({ officeEnabled: true, ocrEnabled: false, ocrLanguages: [] });

    expect(caps.maxUploadMb()).toBe(environment.maxUploadMb);
    expect(caps.maxFilesPerRequest()).toBe(environment.maxFilesPerRequest);
    // The rest of the capability state must still be honoured.
    expect(caps.officeEnabled()).toBeTrue();
  });

  it('keeps the environment fallback when the call fails', () => {
    const caps = service(full, true);

    expect(caps.maxUploadMb()).toBe(environment.maxUploadMb);
    expect(caps.maxFilesPerRequest()).toBe(environment.maxFilesPerRequest);
  });

  it('ignores nonsense values instead of disabling the guard', () => {
    const caps = service({ ...full, maxFileSizeBytes: 0, maxFilesPerRequest: -3 });

    expect(caps.maxUploadMb()).toBe(environment.maxUploadMb);
    expect(caps.maxFilesPerRequest()).toBe(environment.maxFilesPerRequest);
  });

  it('rounds a fractional byte cap DOWN so the client never promises more than the server takes', () => {
    const caps = service({ ...full, maxFileSizeBytes: 25.9 * 1024 * 1024 });

    expect(caps.maxUploadMb()).toBe(25);
  });
});
