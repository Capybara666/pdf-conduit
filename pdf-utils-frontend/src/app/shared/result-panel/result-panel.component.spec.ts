import { ComponentFixture, TestBed } from '@angular/core/testing';

import { ResultPanelComponent } from './result-panel.component';
import { BatchFailuresInfo, RedactionInfo, RepairInfo, RunResult } from '../../core/api.models';
import { TRANSLOCO_TESTING_PROVIDERS, translocoTesting } from '../../testing/transloco-testing';

/**
 * The panel is where a run stops being a byte stream and becomes a statement to
 * the user, so these specs assert on the RENDERED sentence, against the real
 * `en.json` — a missing key would surface as a raw dotted path here.
 *
 * The governing rule is honesty: every claim must be backed by a response
 * header. A header the backend did not send (older deployment, batch response,
 * an intermediary that dropped it) has to degrade to silence or to the existing
 * neutral copy — never to a confident-looking zero, and never to raw wire
 * vocabulary like `startxref-invalid`.
 */
describe('ResultPanelComponent', () => {
  let fixture: ComponentFixture<ResultPanelComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ResultPanelComponent, translocoTesting()],
      providers: [TRANSLOCO_TESTING_PROVIDERS],
    }).compileComponents();
    fixture = TestBed.createComponent(ResultPanelComponent);
  });

  /**
   * A result carrying the given outcome headers. The payload is deliberately
   * NOT typed as a PDF: the preview path would dynamically import pdf.js, which
   * has nothing to do with the copy under test.
   */
  function runResult(extra: Partial<RunResult> = {}): RunResult {
    return {
      blob: new Blob(['data'], { type: 'application/octet-stream' }),
      filename: 'result.bin',
      contentType: 'application/octet-stream',
      ...extra,
    };
  }

  /** A ZIP whose End Of Central Directory advertises `entries` members. */
  function zipOf(entries: number): Blob {
    const eocd = new DataView(new ArrayBuffer(22));
    eocd.setUint32(0, 0x06054b50, true);
    eocd.setUint16(8, entries, true); // entries on this disk
    eocd.setUint16(10, entries, true); // total entries
    return new Blob([eocd.buffer], { type: 'application/zip' });
  }

  async function show(result: RunResult): Promise<void> {
    fixture.componentRef.setInput('result', result);
    fixture.detectChanges();
    // Reading the archive's directory goes through `Blob.arrayBuffer()`, whose
    // native promise `whenStable()` does not track — so give the event loop a
    // few turns, stopping as soon as the count lands (or never, for a result
    // that cannot be counted at all).
    for (let turn = 0; turn < 20 && !fixture.componentInstance.processedCounts; turn++) {
      await fixture.whenStable();
      await new Promise((resolve) => setTimeout(resolve, 0));
    }
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function panel(): HTMLElement {
    return (fixture.nativeElement as HTMLElement).querySelector('.panel')!;
  }

  // --- redaction ----------------------------------------------------------

  describe('redaction coverage', () => {
    it('states what was actually blacked out', async () => {
      await show(runResult({ redaction: { pages: 2, regions: 3 } }));
      expect(text()).toContain('3 areas on 2 pages permanently blacked out.');
    });

    it('uses the singular forms for a single region on a single page', async () => {
      await show(runResult({ redaction: { pages: 1, regions: 1 } }));
      expect(text()).toContain('1 area on 1 page permanently blacked out.');
    });

    it('says nothing about coverage when the headers are absent', async () => {
      await show(runResult());
      expect(text()).toContain('Done');
      expect(text()).not.toContain('blacked out');
    });

    it('never renders a measured-looking zero', async () => {
      // A server that sent 0 regions has told us nothing worth repeating; the
      // panel must not print "0 areas … blacked out" over a redacted file.
      const zero: RedactionInfo = { pages: 0, regions: 0 };
      await show(runResult({ redaction: zero }));
      expect(text()).not.toContain('blacked out');
    });
  });

  // --- repair findings ----------------------------------------------------

  describe('repair findings', () => {
    /** Every backend `RepairFinding` id, and the plain sentence it must become. */
    const MAPPING: ReadonlyArray<[string, string]> = [
      ['header-missing', 'The file had no PDF header at all.'],
      [
        'header-offset',
        'Extra data sat in front of the PDF header (leftovers from an email or a download).',
      ],
      ['eof-missing', 'The end-of-file marker was missing, so the file had been cut short.'],
      ['startxref-missing', 'The file had no pointer to its internal index of contents.'],
      ['startxref-invalid', 'The pointer to the internal index led nowhere usable.'],
      [
        'xref-rebuilt',
        'The internal index could not be read, so it was rebuilt from the content found in the file.',
      ],
      ['rebuild-incomplete', 'Even after rebuilding, parts of the file still do not read correctly.'],
    ];

    for (const [id, copy] of MAPPING) {
      it(`translates "${id}" to plain language`, async () => {
        const repair: RepairInfo = { wasDamaged: true, recovered: true, findings: [id] };
        await show(runResult({ repair }));
        expect(text()).withContext(id).toContain(copy);
        expect(text()).withContext(`${id} must not leak the raw id`).not.toContain(id);
      });
    }

    it('lists several findings under one heading', async () => {
      await show(
        runResult({
          repair: { wasDamaged: true, recovered: true, findings: ['eof-missing', 'xref-rebuilt'] },
        }),
      );
      expect(text()).toContain('What was wrong');
      expect(fixture.nativeElement.querySelectorAll('.findings li').length).toBe(2);
    });

    it('drops an id this build does not know instead of showing it', async () => {
      await show(
        runResult({
          repair: { wasDamaged: true, recovered: true, findings: ['eof-missing', 'trailer-forged'] },
        }),
      );
      expect(text()).toContain('The end-of-file marker was missing');
      expect(text()).not.toContain('trailer-forged');
      expect(fixture.nativeElement.querySelectorAll('.findings li').length).toBe(1);
    });

    it('shows no findings block for an empty list (nothing was wrong)', async () => {
      await show(runResult({ repair: { wasDamaged: false, recovered: false, findings: [] } }));
      expect(text()).toContain('The file was already well-formed');
      expect(text()).not.toContain('What was wrong');
    });

    it('shows no findings block when the header never arrived', async () => {
      // Identical treatment to the empty header: a stripped header is "no
      // findings", never an error.
      await show(runResult({ repair: { wasDamaged: true, recovered: true } }));
      expect(text()).toContain('The file was damaged and has been rebuilt.');
      expect(text()).not.toContain('What was wrong');
    });

    it('reports the rebuilt page count when the server sent one', async () => {
      await show(runResult({ repair: { wasDamaged: true, recovered: true, pageCount: 12 } }));
      expect(text()).toContain('12 pages in the rebuilt file.');
    });

    it('says nothing about pages when the count is absent', async () => {
      await show(runResult({ repair: { wasDamaged: true, recovered: true } }));
      expect(text()).not.toContain('in the rebuilt file');
    });
  });

  // --- partial batch ------------------------------------------------------

  describe('partial batch', () => {
    function failures(entries: BatchFailuresInfo['entries'], more = 0): BatchFailuresInfo {
      return { entries, more, total: entries.length + more, raw: 'raw' };
    }

    it('gets its own state instead of a success panel with a warning glued on', async () => {
      await show(
        runResult({
          filename: 'rotate_results.zip',
          blob: zipOf(12),
          contentType: 'application/zip',
          batchFailures: failures([
            { filename: 'broken.pdf', reason: 'Damaged file.' },
            { filename: 'locked.pdf', reason: 'Password required.' },
          ]),
        }),
      );

      expect(panel().classList).toContain('partial');
      expect(text()).toContain('Some files were skipped');
      expect(text()).withContext('not the plain success headline').not.toContain('Done');
    });

    it('counts the delivered files out of the returned archive', async () => {
      await show(
        runResult({
          filename: 'rotate_results.zip',
          blob: zipOf(12),
          contentType: 'application/zip',
          batchFailures: failures(
            [
              { filename: 'a.pdf', reason: 'Damaged.' },
              { filename: 'b.pdf', reason: 'Damaged.' },
            ],
            1,
          ),
        }),
      );

      expect(text()).toContain('12 of 15 files processed.');
    });

    it('renders a readable list, not one raw header line', async () => {
      await show(
        runResult({
          filename: 'rotate_results.zip',
          blob: zipOf(3),
          contentType: 'application/zip',
          batchFailures: failures(
            [
              { filename: 'broken.pdf', reason: 'Damaged file.' },
              { filename: 'locked.pdf', reason: 'Password required.' },
            ],
            4,
          ),
        }),
      );

      const items = fixture.nativeElement.querySelectorAll('.failures li');
      expect(items.length).withContext('two named failures + the "more" tail').toBe(3);
      expect(items[0].textContent).toContain('broken.pdf');
      expect(items[0].textContent).toContain('Damaged file.');
      expect(items[1].textContent).toContain('locked.pdf');
      expect(items[2].textContent).toContain('…and 4 more files');
      expect(text()).toContain('6 files could not be processed:');
      expect(text()).toContain('Everything that did go through is in the download.');
    });

    it('names a failure the backend gave no reason for', async () => {
      await show(
        runResult({
          filename: 'rotate_results.zip',
          blob: zipOf(1),
          contentType: 'application/zip',
          batchFailures: failures([{ filename: 'odd.pdf', reason: '' }]),
        }),
      );
      expect(text()).toContain('odd.pdf');
      expect(text()).toContain('No reason given.');
    });

    it('drops the "of N" half when the archive cannot be counted', async () => {
      // Not a countable ZIP → the total is unknown, and an unknown total is
      // simply not claimed. The failure count still is.
      await show(
        runResult({
          filename: 'rotate_results.zip',
          contentType: 'application/zip',
          batchFailures: failures([{ filename: 'broken.pdf', reason: 'Damaged file.' }]),
        }),
      );

      expect(text()).not.toContain('files processed.');
      expect(text()).toContain('1 file could not be processed:');
    });

    it('stays the plain success panel when nothing failed', async () => {
      await show(runResult());
      expect(panel().classList).not.toContain('partial');
      expect(text()).toContain('Done');
      expect(text()).not.toContain('could not be processed');
    });
  });
});
