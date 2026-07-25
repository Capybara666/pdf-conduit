import { HttpEventType, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { ApiError, RunResult } from './api.models';
import { ApiService } from './api.service';
import { NOW } from './run-progress';
import { TRANSLOCO_TESTING_PROVIDERS, translocoTesting } from '../testing/transloco-testing';

/**
 * Upload progress + cancellation for the one code path every operation goes
 * through. The behaviour these lock down is not cosmetic: without an upload
 * percentage a slow multi-megabyte POST is minutes of dead air that users read
 * as a hang (they reload and spend a second quota unit), and without a real
 * abort a "Cancel" button would be a lie that still lands a result on the page.
 *
 * Kept in its own file rather than in `api.service.spec.ts`, which pins the
 * response-parsing surface.
 */
describe('ApiService progress + cancel', () => {
  let api: ApiService;
  let http: HttpTestingController;
  /** Fake wall clock so elapsed time is deterministic. */
  let clock: number;

  beforeEach(() => {
    localStorage.clear();
    clock = 1_000_000;
    TestBed.configureTestingModule({
      imports: [translocoTesting()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        TRANSLOCO_TESTING_PROVIDERS,
        { provide: NOW, useValue: () => clock },
      ],
    });
    api = TestBed.inject(ApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    localStorage.clear();
  });

  /** A multipart body with one named file part, as every operation page builds. */
  function body(name = 'a.pdf', size = 2048): FormData {
    const fd = new FormData();
    fd.append('files', new File([new Uint8Array(size)], name, { type: 'application/pdf' }), name);
    return fd;
  }

  it('asks the transport for progress events', () => {
    const run = api.runOperation('merge', body());
    const sub = run.subscribe({ next: () => undefined, error: () => undefined });
    const req = http.expectOne('/api/merge');

    expect(req.request.reportProgress)
      .withContext('reportProgress must stay on or no UploadProgress event is ever emitted')
      .toBe(true);
    sub.unsubscribe();
  });

  it('starts in the uploading phase as soon as the request is sent', () => {
    const run = api.runOperation('merge', body());
    expect(run.run.phase()).toBe('idle');

    const sub = run.subscribe({ next: () => undefined, error: () => undefined });
    http.expectOne('/api/merge');

    expect(run.run.phase()).toBe('uploading');
    expect(run.run.percent()).withContext('no bytes reported yet').toBeNull();
    sub.unsubscribe();
  });

  it('maps upload events to a percentage', () => {
    const run = api.runOperation('merge', body());
    const sub = run.subscribe({ next: () => undefined, error: () => undefined });
    const req = http.expectOne('/api/merge');

    req.event({ type: HttpEventType.UploadProgress, loaded: 250, total: 1000 });
    expect(run.run.percent()).toBe(25);

    req.event({ type: HttpEventType.UploadProgress, loaded: 700, total: 1000 });
    expect(run.run.percent()).toBe(70);
    expect(run.run.phase()).toBe('uploading');
    sub.unsubscribe();
  });

  it('stays indeterminate when the browser cannot compute a total', () => {
    const run = api.runOperation('merge', body());
    const sub = run.subscribe({ next: () => undefined, error: () => undefined });
    const req = http.expectOne('/api/merge');

    req.event({ type: HttpEventType.UploadProgress, loaded: 4096 });

    expect(run.run.percent()).withContext('never fabricate a number').toBeNull();
    expect(run.run.total()).toBeNull();
    expect(run.run.loaded()).toBe(4096);
    expect(run.run.phase()).toBe('uploading');
    sub.unsubscribe();
  });

  it('switches to the processing phase the moment the bytes have landed', () => {
    const run = api.runOperation('merge', body());
    const sub = run.subscribe({ next: () => undefined, error: () => undefined });
    const req = http.expectOne('/api/merge');

    req.event({ type: HttpEventType.UploadProgress, loaded: 1000, total: 1000 });

    expect(run.run.phase())
      .withContext('the bar must not park at 100% while the server works')
      .toBe('processing');
    sub.unsubscribe();
  });

  it('switches to processing when the response starts arriving without upload totals', () => {
    const run = api.runOperation('merge', body());
    const sub = run.subscribe({ next: () => undefined, error: () => undefined });
    const req = http.expectOne('/api/merge');

    req.event({ type: HttpEventType.DownloadProgress, loaded: 10 });

    expect(run.run.phase()).toBe('processing');
    sub.unsubscribe();
  });

  it('records the files being sent for the waiting indicator', () => {
    const fd = body('report.pdf', 4096);
    fd.append('files', new File([new Uint8Array(1024)], 'notes.pdf'), 'notes.pdf');
    fd.append('targetSize', '5MB');

    const run = api.runOperation('compress', fd);
    const sub = run.subscribe({ next: () => undefined, error: () => undefined });
    http.expectOne('/api/compress');

    expect(run.run.files().map((f) => f.name)).toEqual(['report.pdf', 'notes.pdf']);
    expect(run.run.files().map((f) => f.size)).toEqual([4096, 1024]);
    sub.unsubscribe();
  });

  it('ends on done when the result arrives', async () => {
    const run = api.runOperation('merge', body());
    const done = new Promise<RunResult>((resolve, reject) =>
      run.subscribe({ next: resolve, error: reject }),
    );
    http.expectOne('/api/merge').flush(new Blob(['%PDF']), {
      headers: { 'Content-Type': 'application/pdf' },
    });

    await done;
    expect(run.run.phase()).toBe('done');
    expect(run.run.active()).toBeFalse();
  });

  it('ends on failed when the request errors', async () => {
    const run = api.runOperation('merge', body());
    const failed = new Promise<ApiError>((resolve, reject) =>
      run.subscribe({ next: () => reject(new Error('expected a failure')), error: resolve }),
    );
    http.expectOne('/api/merge').flush(new Blob(['']), { status: 500, statusText: 'boom' });

    await failed;
    expect(run.run.phase()).toBe('failed');
  });

  it('marks the run cancelled and emits nothing once the caller unsubscribes', async () => {
    const run = api.runOperation('merge', body());
    let emitted: RunResult | null = null;
    const sub = run.subscribe({ next: (r) => (emitted = r), error: () => undefined });
    const req = http.expectOne('/api/merge');
    req.event({ type: HttpEventType.UploadProgress, loaded: 500, total: 1000 });

    sub.unsubscribe();

    expect(req.cancelled).withContext('the underlying XHR must be aborted').toBeTrue();
    expect(run.run.phase()).toBe('cancelled');
    expect(run.run.active()).toBeFalse();
    expect(emitted).toBeNull();
  });

  it('measures elapsed time from the moment the request was sent', () => {
    const run = api.runOperation('merge', body());
    const sub = run.subscribe({ next: () => undefined, error: () => undefined });
    http.expectOne('/api/merge');

    clock += 12_000;

    expect(run.run.elapsed(clock)).toBe(12_000);
    sub.unsubscribe();
  });
});
