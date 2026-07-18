import { DecimalPipe } from '@angular/common';
import { Component, OnInit, ViewChild, inject, signal } from '@angular/core';
import { TranslocoModule } from '@jsverse/transloco';

import { ApiService } from '../../core/api.service';
import { OperationState } from '../../core/operation-state';
import { RedactHandoffService } from '../../core/redact-handoff.service';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
import { PdfViewerComponent, RegionRect } from '../../shared/pdf-viewer/pdf-viewer.component';
import { ResultPanelComponent } from '../../shared/result-panel/result-panel.component';

/**
 * Interactive redaction: render the PDF with pdf.js, draw rectangles over the
 * pages, and post them to `/api/redact`. The viewer converts each drawn box to
 * PDF points (top-left origin, 0-based page index) before it reaches us, so the
 * `regions` JSON matches the backend `RedactRegionDto` shape exactly.
 */
@Component({
  selector: 'app-redact-page',
  standalone: true,
  imports: [DecimalPipe, TranslocoModule, FileDropZoneComponent, PageHeaderComponent, PdfViewerComponent, ResultPanelComponent],
  template: `
    <section class="op-page wide">
      <app-page-header
        [title]="'pages.redact.title' | transloco"
        [description]="'pages.redact.description' | transloco"
      />

      @if (!file()) {
        <app-file-drop-zone
          [multiple]="false"
          accept=".pdf"
          [hint]="'pages.redact.hint' | transloco"
          (filesChange)="onFile($event.length ? $event[0] : null)"
        />
      }

      @if (file()) {
        <div class="redact-layout">
          <div class="viewer-col card">
            <div class="btn-row" style="margin-bottom:0.75rem">
              <strong class="fname">{{ file()!.name }}</strong>
              <span class="hint-note">{{ 'pages.redact.dragHint' | transloco }}</span>
              <button type="button" class="btn btn-ghost" (click)="reset()">{{ 'pages.redact.chooseAnother' | transloco }}</button>
            </div>
            <app-pdf-viewer
              #viewer
              [file]="file()"
              [drawable]="true"
              [scale]="1.3"
              (loaded)="onViewerLoaded()"
              (regionsChange)="regions.set($event)"
            />
          </div>

          <aside class="side">
            <div class="card">
              <h2 class="side-title">{{ 'pages.redact.regions' | transloco: { count: regions().length } }}</h2>
              @if (!regions().length) {
                <p class="hint-note">{{ 'pages.redact.regionsEmpty' | transloco }}</p>
              } @else {
                <ul class="region-list">
                  @for (r of regions(); track $index; let i = $index) {
                    <li>
                      <span class="rmeta">
                        p{{ r.pageIndex + 1 }} · {{ r.width | number: '1.0-0' }}×{{ r.height | number: '1.0-0' }} pt
                      </span>
                      <button type="button" class="icon-btn" (click)="removeRegion(i)" [attr.aria-label]="'pages.redact.removeRegion' | transloco">✕</button>
                    </li>
                  }
                </ul>
                <button type="button" class="btn btn-ghost" (click)="clear()">{{ 'common.clearAll' | transloco }}</button>
              }
            </div>

            <div class="btn-row">
              <button
                type="button"
                class="btn btn-primary"
                [disabled]="!regions().length || state.loading()"
                (click)="submit()"
              >
                {{ 'pages.redact.submit' | transloco }}
              </button>
            </div>

            <app-result-panel
              [loading]="state.loading()"
              [loadingLabel]="'pages.redact.loading' | transloco"
              [error]="state.error()"
              [result]="state.result()"
            />
          </aside>
        </div>
      }
    </section>
  `,
  styles: [
    `
      .redact-layout {
        display: grid;
        grid-template-columns: minmax(0, 1fr) 300px;
        gap: 1.25rem;
        align-items: start;
      }
      .viewer-col {
        max-height: 80vh;
        overflow: auto;
      }
      .side {
        display: flex;
        flex-direction: column;
        gap: 1rem;
        position: sticky;
        top: 1rem;
      }
      .side-title {
        margin: 0 0 0.75rem;
        font-size: 1rem;
      }
      .fname {
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        max-width: 40%;
      }
      .region-list {
        list-style: none;
        margin: 0 0 0.75rem;
        padding: 0;
        display: flex;
        flex-direction: column;
        gap: 0.35rem;
      }
      .region-list li {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: 0.3rem 0.5rem;
        background: var(--surface-2);
        border-radius: 6px;
      }
      .rmeta {
        font-size: 0.8rem;
        font-variant-numeric: tabular-nums;
      }
      @media (max-width: 820px) {
        .redact-layout {
          grid-template-columns: 1fr;
        }
        .side {
          position: static;
        }
      }
    `,
  ],
})
export class RedactPage implements OnInit {
  @ViewChild('viewer') private viewer?: PdfViewerComponent;

  protected readonly file = signal<File | null>(null);
  protected readonly regions = signal<RegionRect[]>([]);
  protected readonly state = new OperationState();

  private readonly handoff = inject(RedactHandoffService);

  /**
   * Regions handed off from the GDPR scan, waiting to be drawn once the viewer
   * has loaded the file. Seeded before draw (regions cleared during reload), so
   * we defer to the viewer's `loaded` event, then clear this so re-picking a
   * file later doesn't re-seed.
   */
  private pendingRegions: RegionRect[] = [];

  constructor(private readonly api: ApiService) {}

  ngOnInit(): void {
    const h = this.handoff.consume();
    if (!h) return;
    // Same point space as the viewer's internal regions (top-left, 0-based,
    // display-page points) — no conversion needed, just seed after load.
    this.pendingRegions = h.regions;
    this.file.set(h.file);
    this.regions.set([]);
    this.state.reset();
  }

  /**
   * The viewer clears regions during its reload, so pre-drawn handoff boxes are
   * applied here — after pages exist and before the user interacts. Cleared so a
   * subsequent file choice starts blank.
   */
  onViewerLoaded(): void {
    if (!this.pendingRegions.length) return;
    this.viewer?.setRegions(this.pendingRegions);
    this.pendingRegions = [];
  }

  onFile(f: File | null): void {
    this.pendingRegions = [];
    this.file.set(f);
    this.regions.set([]);
    this.state.reset();
  }

  reset(): void {
    this.onFile(null);
  }

  removeRegion(i: number): void {
    this.viewer?.removeRegion(i);
  }

  clear(): void {
    this.viewer?.clearRegions();
  }

  submit(): void {
    const f = this.file();
    if (!f || !this.regions().length) return;
    // Match RedactRegionDto: { pageIndex, x, y, width, height }.
    const payload = this.regions().map((r) => ({
      pageIndex: r.pageIndex,
      x: r.x,
      y: r.y,
      width: r.width,
      height: r.height,
    }));
    const fd = new FormData();
    fd.append('file', f, f.name);
    fd.append('regions', JSON.stringify(payload));
    this.state.run(this.api.redact(fd));
  }
}
