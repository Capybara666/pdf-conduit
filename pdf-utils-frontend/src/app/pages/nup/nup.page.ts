import { Component, inject, signal } from '@angular/core';
import { TranslocoModule } from '@jsverse/transloco';

import { ApiService } from '../../core/api.service';
import { OperationState } from '../../core/operation-state';
import { WorkStateService } from '../../core/work-state.service';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
import { ResultPanelComponent } from '../../shared/result-panel/result-panel.component';

type NupLayout = '2up' | '4up' | '6up' | '8up' | '9up';
/** Booklet is imposition, not a grid preset, but the user picks it like a layout. */
type LayoutChoice = NupLayout | 'booklet';

/** One numbered cell in a layout pictogram (SVG user units, 0..VB). */
interface PicCell {
  x: number;
  y: number;
  w: number;
  h: number;
  cx: number;
  cy: number;
  n: number;
}

/** A selectable layout, with its precomputed pictogram geometry. */
interface LayoutOpt {
  value: LayoutChoice;
  labelKey: string;
  booklet: boolean;
  cells: PicCell[];
  /** Polyline points ("cx,cy cx,cy …") tracing page reading order. */
  order: string;
  fontSize: number;
}

// Pictogram viewBox + spacing. Numbers are drawn 1..N in reading order so the
// user sees both the arrangement and the order before running.
const VB_W = 36;
const VB_H = 26;
const PAD = 2.5;
const GAP = 1.6;

function buildLayout(
  value: LayoutChoice,
  labelKey: string,
  cols: number,
  rows: number,
  booklet = false,
): LayoutOpt {
  const cw = (VB_W - PAD * 2) / cols;
  const ch = (VB_H - PAD * 2) / rows;
  const cells: PicCell[] = [];
  for (let i = 0; i < cols * rows; i++) {
    const c = i % cols;
    const r = Math.floor(i / cols);
    const x = PAD + c * cw + GAP / 2;
    const y = PAD + r * ch + GAP / 2;
    const w = cw - GAP;
    const h = ch - GAP;
    cells.push({ x, y, w, h, cx: x + w / 2, cy: y + h / 2, n: i + 1 });
  }
  const order = cells.map((c) => `${round(c.cx)},${round(c.cy)}`).join(' ');
  const fontSize = Math.max(3, Math.min(7, Math.min(cw, ch) * 0.55));
  return { value, labelKey, booklet, cells, order, fontSize };
}

const round = (n: number): number => Math.round(n * 10) / 10;

/** N-up / booklet imposition: place several source pages onto each output sheet. */
@Component({
  selector: 'app-nup-page',
  standalone: true,
  imports: [TranslocoModule, FileDropZoneComponent, PageHeaderComponent, ResultPanelComponent],
  template: `
    <section class="op-page">
      <app-page-header
        [title]="'pages.nup.title' | transloco"
        [description]="'pages.nup.description' | transloco"
      />

      <app-file-drop-zone
        [multiple]="true"
        accept=".pdf"
        [hint]="'pages.nup.hint' | transloco"
        (filesChange)="files.set($event)"
      />

      <p class="hint-note" role="note">{{ 'pages.nup.privacyLine' | transloco }}</p>
      @if (files().length > 1) {
        <p class="help">{{ 'pages.nup.batchNote' | transloco: { count: files().length } }}</p>
      }

      <div class="card">
        <div class="field full">
          <label id="nup-layout-label">{{ 'pages.nup.layout' | transloco }}</label>
          <div
            class="seg nup-seg"
            role="group"
            aria-labelledby="nup-layout-label"
            style="flex-wrap:wrap"
          >
            @for (l of LAYOUTS; track l.value) {
              <button
                type="button"
                [class.active]="layout() === l.value"
                [attr.aria-pressed]="layout() === l.value"
                (click)="layout.set(l.value)"
                style="display:flex;flex-direction:column;align-items:center;gap:3px;line-height:1"
              >
                <svg
                  viewBox="0 0 36 26"
                  width="42"
                  height="30"
                  fill="none"
                  aria-hidden="true"
                  style="display:block"
                >
                  @if (!l.booklet && l.cells.length > 1) {
                    <defs>
                      <marker
                        [attr.id]="arrowId(l)"
                        markerWidth="4"
                        markerHeight="4"
                        refX="3"
                        refY="2"
                        orient="auto"
                        markerUnits="userSpaceOnUse"
                      >
                        <path d="M0,0 L4,2 L0,4 Z" fill="currentColor" stroke="none" />
                      </marker>
                    </defs>
                    <polyline
                      [attr.points]="l.order"
                      fill="none"
                      stroke="currentColor"
                      stroke-width="0.7"
                      stroke-opacity="0.4"
                      [attr.marker-end]="'url(#' + arrowId(l) + ')'"
                    />
                  }
                  @for (cell of l.cells; track cell.n) {
                    <rect
                      [attr.x]="cell.x"
                      [attr.y]="cell.y"
                      [attr.width]="cell.w"
                      [attr.height]="cell.h"
                      rx="1.2"
                      fill="none"
                      stroke="currentColor"
                      stroke-width="1"
                    />
                    @if (!l.booklet) {
                      <text
                        [attr.x]="cell.cx"
                        [attr.y]="cell.cy"
                        text-anchor="middle"
                        dominant-baseline="central"
                        stroke="none"
                        fill="currentColor"
                        [attr.font-size]="l.fontSize"
                        font-weight="600"
                      >{{ cell.n }}</text>
                    }
                  }
                  @if (l.booklet) {
                    <!-- Center saddle-stitch fold: booklet imposes its own 2-up order. -->
                    <line
                      x1="18"
                      y1="3"
                      x2="18"
                      y2="23"
                      stroke="currentColor"
                      stroke-width="0.8"
                      stroke-dasharray="1.8 1.6"
                      stroke-opacity="0.75"
                    />
                  }
                </svg>
                <span>{{ l.labelKey | transloco }}</span>
              </button>
            }
          </div>
          @if (layout() === 'booklet') {
            <span class="help" style="margin-top:0.5rem">{{
              'pages.nup.bookletHelp' | transloco
            }}</span>
          }
        </div>
      </div>

      <div class="btn-row">
        <button
          type="button"
          class="btn btn-primary"
          [disabled]="!files().length || state.loading()"
          (click)="submit()"
        >
          {{ 'pages.nup.submit' | transloco }}
          @if (files().length) {
            · {{ 'common.fileCount' | transloco: { count: files().length } }}
          }
        </button>
        <button type="button" class="btn" (click)="clear()">{{ 'common.clear' | transloco }}</button>
      </div>

      <app-result-panel
        [loading]="state.loading()"
        [loadingLabel]="'pages.nup.loading' | transloco"
        [error]="state.error()"
        [result]="state.result()"
        (retry)="submit()"
      />
    </section>
  `,
})
export class NupPage {
  protected readonly files = signal<File[]>([]);
  protected readonly layout = signal<LayoutChoice>('2up');
  protected readonly state = new OperationState();

  /** Segmented layout options; booklet is folded in as the last segment. */
  protected readonly LAYOUTS: LayoutOpt[] = [
    buildLayout('2up', 'pages.nup.layout2up', 2, 1),
    buildLayout('4up', 'pages.nup.layout4up', 2, 2),
    buildLayout('6up', 'pages.nup.layout6up', 3, 2),
    buildLayout('8up', 'pages.nup.layout8up', 4, 2),
    buildLayout('9up', 'pages.nup.layout9up', 3, 3),
    buildLayout('booklet', 'pages.nup.booklet', 2, 1, true),
  ];

  private readonly workState = inject(WorkStateService);

  constructor(private readonly api: ApiService) {
    this.workState.persist('nup', { layout: this.layout });
  }

  /** Unique arrowhead marker id per layout (avoids cross-SVG collisions). */
  protected arrowId(l: LayoutOpt): string {
    return `nup-arrow-${l.value}`;
  }

  clear(): void {
    this.workState.reset('nup');
    this.files.set([]);
    this.state.reset();
  }

  submit(): void {
    if (!this.files().length) return;
    const fd = new FormData();
    for (const f of this.files()) fd.append('files', f, f.name);
    // Booklet imposes its own 2-up saddle-stitch order; otherwise send the grid
    // preset. Same params the API always accepted — only the selector changed.
    if (this.layout() === 'booklet') fd.append('booklet', 'true');
    else fd.append('layout', this.layout());
    this.state.run(this.api.nup(fd));
  }
}
