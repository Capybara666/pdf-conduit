import { ComponentFixture, TestBed } from '@angular/core/testing';

import { FileDropZoneComponent, FileRejection } from './file-drop-zone.component';
import { environment } from '../../../environments/environment';
import { TRANSLOCO_TESTING_PROVIDERS, translocoTesting } from '../../testing/transloco-testing';

/**
 * The drop zone is the only place the client decides what is worth uploading,
 * so its two caps must track the backend free tier: too low and we refuse work
 * the service would have done, too high and the user waits out a doomed upload
 * for a 413.
 */
describe('FileDropZoneComponent', () => {
  let fixture: ComponentFixture<FileDropZoneComponent>;
  let component: FileDropZoneComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [FileDropZoneComponent, translocoTesting()],
      providers: [TRANSLOCO_TESTING_PROVIDERS],
    }).compileComponents();

    fixture = TestBed.createComponent(FileDropZoneComponent);
    component = fixture.componentInstance;
  });

  function file(name: string, bytes = 8): File {
    return new File([new Uint8Array(bytes)], name, { type: 'application/pdf' });
  }

  /** Drop files onto the zone through the real public handler. */
  function drop(files: File[]): void {
    component.onDrop({
      preventDefault: () => undefined,
      dataTransfer: { files },
    } as unknown as DragEvent);
    fixture.detectChanges();
  }

  it('defaults both caps to the configured backend free-tier limits', () => {
    expect(component.maxFileSizeMb).toBe(environment.maxUploadMb);
    expect(component.maxFiles).toBe(environment.maxFilesPerRequest);
    // A drop zone with no count cap was the bug: the backend rejects the batch
    // only after the whole upload has been sent.
    expect(component.maxFiles).toBeGreaterThan(0);
  });

  it('accepts up to maxFiles and rejects the rest with reason "count"', () => {
    const cap = component.maxFiles;
    const rejected: FileRejection[][] = [];
    component.rejected.subscribe((r) => rejected.push(r));

    drop(Array.from({ length: cap + 2 }, (_, i) => file(`doc-${i}.pdf`)));

    expect(component.files.length).toBe(cap);
    expect(rejected.length).toBe(1);
    expect(rejected[0].length).toBe(2);
    expect(rejected[0].every((r) => r.reason === 'count')).toBeTrue();
    expect(rejected[0][0].file.name).toBe(`doc-${cap}.pdf`);
  });

  it('counts files already selected towards the cap', () => {
    component.maxFiles = 3;
    drop([file('a.pdf'), file('b.pdf')]);
    drop([file('c.pdf'), file('d.pdf')]);

    expect(component.files.map((f) => f.name)).toEqual(['a.pdf', 'b.pdf', 'c.pdf']);
    expect(component.rejections().map((r) => r.reason)).toEqual(['count']);
  });

  it('treats maxFiles = 0 as an explicit opt-out', () => {
    component.maxFiles = 0;
    drop(Array.from({ length: environment.maxFilesPerRequest + 5 }, (_, i) => file(`d${i}.pdf`)));

    expect(component.files.length).toBe(environment.maxFilesPerRequest + 5);
    expect(component.rejections()).toEqual([]);
  });

  it('does not apply the count cap in single-file mode', () => {
    component.multiple = false;
    drop([file('one.pdf'), file('two.pdf')]);

    expect(component.files.map((f) => f.name)).toEqual(['one.pdf']);
    expect(component.rejections()).toEqual([]);
  });

  it('rejects an oversize file with reason "size"', () => {
    component.maxFileSizeMb = 1;
    drop([file('big.pdf', 2 * 1024 * 1024), file('small.pdf')]);

    expect(component.files.map((f) => f.name)).toEqual(['small.pdf']);
    expect(component.rejections().map((r) => r.reason)).toEqual(['size']);
  });

  it('states both limits in the localised constraints line', () => {
    component.accept = '.pdf';
    component.maxFileSizeMb = 25;
    component.maxFiles = 15;

    // Real en.json copy — a missing key would surface as the raw dotted key.
    expect(component.constraintsText).toBe('PDF · up to 25 MB · max 15 files');
  });
});
