import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { By } from '@angular/platform-browser';

import { OpProgressComponent } from './op-progress.component';
import { NOW, RunTracker } from '../../core/run-progress';
import { TRANSLOCO_TESTING_PROVIDERS, translocoTesting } from '../../testing/transloco-testing';

/**
 * The waiting indicator. What is being pinned here is honesty and calm:
 * a determinate bar ONLY while bytes are measurably going up, an indeterminate
 * one (never a parked 100%, never an invented ETA) while the server works,
 * copy that escalates with the real elapsed time, and announcements that fire
 * on phase changes rather than on every tick.
 *
 * Time is driven by a fake clock + `fakeAsync`, so nothing here waits on a real
 * timer.
 */
describe('OpProgressComponent', () => {
  let fixture: ComponentFixture<OpProgressComponent>;
  let clock: number;

  beforeEach(() => {
    clock = 500_000;
    TestBed.configureTestingModule({
      imports: [OpProgressComponent, translocoTesting()],
      providers: [TRANSLOCO_TESTING_PROVIDERS, { provide: NOW, useValue: () => clock }],
    });
    fixture = TestBed.createComponent(OpProgressComponent);
  });

  /** A tracker on the same fake clock as the component. */
  function tracker(): RunTracker {
    return new RunTracker(() => clock);
  }

  function show(run: RunTracker | null, label = 'Compressing…'): void {
    fixture.componentRef.setInput('run', run);
    fixture.componentRef.setInput('label', label);
    fixture.detectChanges();
  }

  /** Advance the fake wall clock AND the periodic timer that re-reads it. */
  function advance(ms: number): void {
    clock += ms;
    tick(ms);
    fixture.detectChanges();
  }

  function text(selector: string): string {
    const el = fixture.debugElement.query(By.css(selector));
    return el ? (el.nativeElement as HTMLElement).textContent!.replace(/\s+/g, ' ').trim() : '';
  }

  function bar(): HTMLElement | null {
    const el = fixture.debugElement.query(By.css('[role="progressbar"]'));
    return el ? (el.nativeElement as HTMLElement) : null;
  }

  it('renders nothing without a run', () => {
    show(null);
    expect(fixture.nativeElement.textContent.trim()).toBe('');
  });

  it('shows a determinate bar with the real upload percentage', () => {
    const run = tracker();
    run.begin([{ name: 'big.pdf', size: 1000 }]);
    run.upload(250, 1000);
    show(run);

    expect(bar()!.getAttribute('aria-valuenow')).toBe('25');
    expect(bar()!.getAttribute('aria-valuetext')).toBe('Uploading 25%');
    expect(bar()!.classList).not.toContain('indeterminate');
    expect(text('.numbers')).toContain('25%');
  });

  it('falls back to an indeterminate bar when the total is unknown', () => {
    const run = tracker();
    run.begin();
    run.upload(4096);
    show(run);

    expect(bar()!.classList).toContain('indeterminate');
    expect(bar()!.getAttribute('aria-valuenow'))
      .withContext('an indeterminate progressbar must not claim a value')
      .toBeNull();
    expect(text('.numbers')).toBe('4.0 KB sent');
  });

  it('never renders a full bar while it still says uploading', () => {
    const run = tracker();
    run.begin();
    run.upload(9_995, 10_000); // rounds to 100
    show(run);

    expect(bar()!.getAttribute('aria-valuenow')).toBe('99');
  });

  it('goes indeterminate and says it is working once the upload lands', () => {
    const run = tracker();
    run.begin();
    run.upload(1000, 1000);
    show(run);

    expect(bar()!.classList).toContain('indeterminate');
    expect(text('.message')).toBe('Working on your files…');
  });

  it('announces the phase politely, without the percentage', () => {
    const run = tracker();
    run.begin();
    run.upload(250, 1000);
    show(run);

    const live = fixture.debugElement.query(By.css('.message')).nativeElement as HTMLElement;
    expect(live.getAttribute('role')).toBe('status');
    expect(live.getAttribute('aria-live')).toBe('polite');
    expect(live.textContent!.trim()).toBe('Uploading…');
  });

  it('holds the elapsed readout back until the wait is non-trivial', fakeAsync(() => {
    const run = tracker();
    run.begin();
    run.processing();
    show(run);

    expect(text('.elapsed')).toBe('');

    advance(2000);
    expect(text('.elapsed')).withContext('2s is not worth timing').toBe('');

    advance(1000);
    expect(text('.elapsed')).toBe('0:03 elapsed');

    advance(58_000);
    expect(text('.elapsed')).toBe('1:01 elapsed');

    fixture.destroy();
  }));

  it('escalates the message honestly as the wait grows', fakeAsync(() => {
    const run = tracker();
    run.begin();
    run.processing();
    show(run);

    expect(text('.message')).toBe('Working on your files…');

    advance(10_000);
    expect(text('.message')).toBe('This is a bigger job. Still going.');

    advance(20_000); // 30s
    expect(text('.message')).toBe('Still working. Large files can take a while.');

    advance(60_000); // 90s
    expect(text('.message')).toBe(
      'This is taking longer than usual. You can keep waiting, or cancel and try a smaller file.',
    );

    fixture.destroy();
  }));

  it('never invents an ETA or a percentage for the server-side wait', fakeAsync(() => {
    const run = tracker();
    run.begin();
    run.processing();
    show(run);
    advance(45_000);

    expect(bar()!.hasAttribute('aria-valuenow')).toBeFalse();
    expect(text('.numbers')).toBe('');

    fixture.destroy();
  }));

  it('summarises what is being processed', () => {
    const single = tracker();
    single.begin([{ name: 'report.pdf', size: 2048 }]);
    show(single);
    expect(text('.files')).toBe('report.pdf · 2.0 KB');

    const batch = tracker();
    batch.begin([
      { name: 'a.pdf', size: 1024 },
      { name: 'b.pdf', size: 1024 },
      { name: 'c.pdf', size: 1024 },
    ]);
    show(batch);
    expect(text('.files')).toBe('3 files · 3.0 KB');
  });

  it('offers cancel while waiting and emits it', () => {
    const run = tracker();
    run.begin();
    show(run);

    let cancelled = 0;
    fixture.componentInstance.cancel.subscribe(() => cancelled++);
    const button = fixture.debugElement.query(By.css('.cancel')).nativeElement as HTMLButtonElement;
    expect(button.textContent!.trim()).toBe('Cancel');

    button.click();
    expect(cancelled).toBe(1);
  });

  it('replaces the wait with the honest post-cancel caveat', () => {
    const run = tracker();
    run.begin();
    run.cancel();
    show(run);

    expect(fixture.debugElement.query(By.css('.op-progress'))).toBeNull();
    const note = fixture.debugElement.query(By.css('.op-cancelled'));
    expect(note).not.toBeNull();
    expect(text('.op-cancelled .note')).toBe(
      'You stopped waiting for this result. Our server may still finish the job, ' +
        "and it still counts toward today's free limit.",
    );
  });

  it('stops ticking once the run is over', fakeAsync(() => {
    const run = tracker();
    run.begin();
    run.processing();
    show(run);
    advance(5000);
    expect(text('.elapsed')).toBe('0:05 elapsed');

    run.succeed();
    advance(5000);

    // Nothing is rendered any more and the periodic timer has been dropped
    // (fakeAsync would fail on a leftover one when the spec ends).
    expect(fixture.debugElement.query(By.css('.op-progress'))).toBeNull();
  }));
});
