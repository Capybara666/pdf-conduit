import { Observable, Subject } from 'rxjs';

import { ApiError, RunResult } from './api.models';
import { OperationState } from './operation-state';
import { RunTracker, withRunTracker } from './run-progress';

/**
 * The lifecycle holder every operation page shares. The cancel path is the
 * interesting one: it has to tear down the subscription (which is what aborts
 * the XHR) AND leave the page in a clean, retryable state — no stale spinner,
 * no half-populated result — while still admitting that the server may finish
 * the job anyway (the tracker stays on `cancelled` so the page can say so).
 */
describe('OperationState', () => {
  let source: Subject<RunResult>;
  let tracker: RunTracker;
  let unsubscribed: number;
  let state: OperationState;

  const RESULT: RunResult = {
    blob: new Blob(['%PDF']),
    filename: 'out.pdf',
    contentType: 'application/pdf',
  };

  beforeEach(() => {
    source = new Subject<RunResult>();
    unsubscribed = 0;
    tracker = new RunTracker(() => 0);
    state = new OperationState();
  });

  /** An operation observable that counts teardowns, like the real aborting request. */
  function operation(): Observable<RunResult> {
    const obs = new Observable<RunResult>((subscriber) => {
      const sub = source.subscribe(subscriber);
      return () => {
        unsubscribed++;
        sub.unsubscribe();
      };
    });
    return withRunTracker(obs, tracker);
  }

  it('adopts the tracker attached to the request', () => {
    state.run(operation());

    expect(state.tracker()).toBe(tracker);
    expect(state.loading()).toBeTrue();
  });

  it('creates a tracker for a plain observable so the wait is still visible', () => {
    state.run(new Observable<RunResult>(() => undefined));

    expect(state.tracker()).not.toBeNull();
    expect(state.tracker()!.active()).toBeTrue();
    expect(state.tracker()!.phase())
      .withContext('nothing measurable is known, so do not claim to be uploading')
      .toBe('processing');
    expect(state.tracker()!.percent()).toBeNull();
  });

  it('mirrors a successful result and stops loading', () => {
    state.run(operation());
    tracker.begin();
    source.next(RESULT);

    expect(state.result()).toBe(RESULT);
    expect(state.loading()).toBeFalse();
    expect(state.error()).toBeNull();
  });

  it('cancel unsubscribes and returns to a clean, retryable state', () => {
    state.run(operation());
    tracker.begin();

    state.cancel();

    expect(unsubscribed).withContext('the request must be torn down').toBe(1);
    expect(state.loading()).toBeFalse();
    expect(state.result()).toBeNull();
    expect(state.error()).toBeNull();
    expect(state.tracker()!.phase()).toBe('cancelled');
  });

  it('populates no result from a cancelled request', () => {
    state.run(operation());
    state.cancel();

    // A late-arriving response must not resurrect the page.
    source.next(RESULT);

    expect(state.result()).toBeNull();
    expect(state.loading()).toBeFalse();
  });

  it('records no error for a cancelled request', () => {
    state.run(operation());
    state.cancel();

    source.error(new ApiError('operation_failed', 'too late', 422));

    expect(state.error()).toBeNull();
  });

  it('ignores cancel when nothing is in flight', () => {
    state.run(operation());
    source.next(RESULT);

    state.cancel();

    expect(state.result()).withContext('a delivered result survives a stray cancel').toBe(RESULT);
    expect(unsubscribed).toBe(0);
  });

  it('keeps the cancelled note until it is dismissed or a new run starts', () => {
    state.run(operation());
    state.cancel();
    expect(state.tracker()).not.toBeNull();

    state.dismiss();
    expect(state.tracker()).toBeNull();
  });

  it('reset aborts anything in flight and clears everything', () => {
    state.run(operation());

    state.reset();

    expect(unsubscribed).toBe(1);
    expect(state.tracker()).toBeNull();
    expect(state.loading()).toBeFalse();
    expect(state.result()).toBeNull();
  });

  it('a new run aborts the previous one', () => {
    state.run(operation());
    state.run(operation());

    expect(unsubscribed).toBe(1);
    expect(state.loading()).toBeTrue();
  });
});
