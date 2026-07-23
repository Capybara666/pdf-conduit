import { AfterViewInit, Component, OnInit, ViewChild, computed, inject, signal } from '@angular/core';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';

import { ApiError, RunResult } from '../../core/api.models';
import { ApiService } from '../../core/api.service';
import { downloadRunResult } from '../../core/download.util';
import { NodeKindInfo, NodeKindName, PipelineValidationError } from '../../core/pipeline.models';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
import { OpIconComponent } from '../../shared/op-icon/op-icon.component';
import { SpinnerComponent } from '../../shared/spinner/spinner.component';
import { PipelineCanvasComponent } from './canvas/pipeline-canvas.component';
import { PipelineInspectorComponent } from './canvas/pipeline-inspector.component';

/**
 * Palette fallback if `GET /api/pipeline/kinds` is unavailable. Field names and
 * flags mirror the backend `/api/pipeline/kinds` wire shape (minus SOURCE, which
 * the palette adds via its own chip): `isReduce` marks Merge, `isExport` marks
 * the non-PDF terminals. Names match NodeKind.
 */
const FALLBACK_KINDS: NodeKindInfo[] = [
  { name: 'MERGE', label: 'Merge', isSource: false, isReduce: true, isExport: false },
  { name: 'IMAGES_TO_PDF', label: 'To PDF', isSource: false, isReduce: false, isExport: false },
  { name: 'EXTRACT', label: 'Extract', isSource: false, isReduce: false, isExport: false },
  { name: 'COMPRESS', label: 'Compress', isSource: false, isReduce: false, isExport: false },
  { name: 'ROTATE', label: 'Rotate', isSource: false, isReduce: false, isExport: false },
  { name: 'ARRANGE', label: 'Arrange', isSource: false, isReduce: false, isExport: false },
  { name: 'PROTECT', label: 'Protect', isSource: false, isReduce: false, isExport: false },
  { name: 'UNLOCK', label: 'Unlock', isSource: false, isReduce: false, isExport: false },
  { name: 'METADATA', label: 'Metadata', isSource: false, isReduce: false, isExport: false },
  { name: 'WATERMARK', label: 'Watermark', isSource: false, isReduce: false, isExport: false },
  { name: 'NUP', label: 'N-up', isSource: false, isReduce: false, isExport: false },
  { name: 'PAGE_MARKS', label: 'Page Marks', isSource: false, isReduce: false, isExport: false },
  { name: 'TO_IMAGES', label: 'To Images', isSource: false, isReduce: false, isExport: true },
  { name: 'TO_TEXT', label: 'To Text', isSource: false, isReduce: false, isExport: true },
];

/** NodeKind → operation id used for i18n label lookup (`op.<id>.label`) + glyph. */
const KIND_TO_OP: Record<string, string> = {
  SOURCE: 'source',
  MERGE: 'merge',
  IMAGES_TO_PDF: 'to-pdf',
  EXTRACT: 'extract',
  COMPRESS: 'compress',
  ROTATE: 'rotate',
  ARRANGE: 'arrange',
  PROTECT: 'protect',
  UNLOCK: 'unlock',
  METADATA: 'metadata',
  WATERMARK: 'watermark',
  NUP: 'nup',
  PAGE_MARKS: 'page-marks',
  TO_IMAGES: 'to-images',
  TO_TEXT: 'to-text',
};

/**
 * Free-form visual pipeline builder: a draggable-node canvas with drawn
 * connections (mirrors the desktop pipeline editor). The palette adds nodes, a
 * drop-zone feeds SOURCE nodes, the inspector edits the selected node's params,
 * and Run assembles the `PipelineModelJson` (real drag coordinates + SOURCE
 * file basenames) and posts a multipart request, streaming back a ZIP.
 */
@Component({
  selector: 'app-pipeline-page',
  standalone: true,
  imports: [
    TranslocoModule,
    PageHeaderComponent,
    OpIconComponent,
    SpinnerComponent,
    PipelineCanvasComponent,
    PipelineInspectorComponent,
  ],
  template: `
    <section class="op-page pl-page">
      <app-page-header
        [title]="'pages.pipeline.title' | transloco"
        [description]="'pages.pipeline.description' | transloco"
      />

      <!-- Small-viewport advisory: the node canvas is pointer-oriented and reads best on a wider screen. -->
      <p class="hint-note mobile-hint">{{ 'pipeline.mobileHint' | transloco }}</p>

      <!-- Palette + file-actions toolbar. Two distinct concerns kept visually
           apart: the node palette (adds nodes, grouped by kind) sits below a
           header row whose right cluster holds the file actions. -->
      <div class="card palette-card">
        <div class="pl-toolbar">
          <span class="pl-toolbar-h">{{ 'pipeline.canvas.addNode' | transloco }}</span>
          <div class="file-actions" role="group" aria-label="Pipeline file actions">
            <button type="button" class="btn btn-ghost" (click)="save()" [disabled]="!cv.nodes().length">{{ 'pipeline.canvas.save' | transloco }}</button>
            <button type="button" class="btn btn-ghost" (click)="loadInput.click()">{{ 'pipeline.canvas.load' | transloco }}</button>
            <button type="button" class="btn btn-ghost btn-danger" (click)="cv.clear()" [disabled]="!cv.nodes().length">{{ 'pipeline.canvas.clear' | transloco }}</button>
            <input #loadInput type="file" accept="application/json,.json" hidden (change)="load($event)" />
          </div>
        </div>

        <div class="palette-groups">
          <!-- Source is always available and adds a file drop-zone node. -->
          <div class="palette-group">
            <!-- TODO(i18n): localize palette group label "Source". -->
            <span class="group-label">Source</span>
            <div class="chips">
              <button type="button" class="btn chip" (click)="cv.addNode('SOURCE')"><app-op-icon class="chip-ico" name="source" />{{ 'pipeline.canvas.source' | transloco }}</button>
            </div>
          </div>

          @for (g of groups(); track g.key) {
            <div class="palette-group">
              <span class="group-label">{{ g.label }}</span>
              <div class="chips">
                @for (k of g.kinds; track k.name) {
                  <button type="button" class="btn chip" (click)="cv.addNode(k.name)"><app-op-icon class="chip-ico" [name]="iconFor(k.name)" />{{ nodeLabel(k.name) }}</button>
                }
              </div>
            </div>
          }
        </div>
      </div>

      <div class="pl-grid" [class.collapsed]="!panelOpen()">
        <!-- Canvas -->
        <div class="col">
          <app-pipeline-canvas #cv />
          <p class="hint-note canvas-help">{{ 'pipeline.canvas.help' | transloco }}</p>
        </div>

        <!-- Right dock: persistent toggle rail + collapsible drawer.
             Collapsing drops the grid to a single column, giving the canvas full width. -->
        <div class="dock">
          <!-- TODO(i18n): localize "Show inspector" / "Hide inspector" aria labels. -->
          <button
            type="button"
            class="rail-toggle"
            [attr.aria-expanded]="panelOpen()"
            [attr.aria-label]="panelOpen() ? 'Hide inspector panel' : 'Show inspector panel'"
            [title]="panelOpen() ? 'Hide panel' : 'Show panel'"
            (click)="togglePanel()"
          >
            <span class="rail-chevron">{{ panelOpen() ? '»' : '«' }}</span>
            @if (!panelOpen()) { <span class="rail-label">{{ 'pipeline.canvas.inspector' | transloco }}</span> }
          </button>

          @if (panelOpen()) {
            <aside class="col side">
              <div class="card">
                <h2 class="card-h">{{ 'pipeline.canvas.inspector' | transloco }}</h2>
                <app-pipeline-inspector
                  [node]="cv.selectedNode()"
                  [sourceFiles]="cv.selectedSourceFiles()"
                  (patch)="cv.patchSelected($event)"
                  (asset)="cv.setSelectedAsset($event)"
                  (sourceFilesChange)="cv.setSelectedSourceFiles($event)"
                />
              </div>

              <div class="card">
                <div class="btn-row">
                  <button type="button" class="btn" [disabled]="busy() || !cv.nodes().length" (click)="validate(cv)">{{ 'pages.pipeline.validate' | transloco }}</button>
                  <button type="button" class="btn btn-primary" [disabled]="!cv.runnable() || busy()" (click)="run(cv)">{{ 'pages.pipeline.run' | transloco }}</button>
                </div>
                @if (busy()) { <app-spinner [label]="'pages.pipeline.running' | transloco" /> }

                @if (validationErrors() !== null) {
                  @if (validationErrors()!.length) {
                    <ul class="verr">
                      @for (e of validationErrors()!; track $index) {
                        <li>{{ e.nodeId ? '[' + e.nodeId + '] ' : '' }}{{ e.message }}</li>
                      }
                    </ul>
                  } @else {
                    <p class="ok-note">{{ 'pages.pipeline.valid' | transloco }}</p>
                  }
                }
                @if (error()) { <p class="verr-line">{{ error()!.message }}</p> }
                @if (result()) {
                  <div class="done-box">
                    <p class="filename">{{ result()!.filename }}</p>
                    <button type="button" class="btn btn-primary" (click)="download()">{{ 'common.downloadZip' | transloco }}</button>
                  </div>
                }
              </div>
            </aside>
          }
        </div>
      </div>
    </section>
  `,
  styles: [
    `
      /* Pipeline is a canvas TOOL that needs horizontal room for the node
         surface + the side-docked inspector drawer. The shared '.op-page'
         container is capped at a narrow reading width (~860px) in the global
         FIXED width mode, which is too tight for canvas + 300px drawer and
         forces the drawer to overlap the canvas. Override the cap here so the
         pipeline always gets a GENEROUS width regardless of the global
         data-width toggle — this rule is component-scoped (Angular emulated
         encapsulation), and '.op-page.pl-page' outranks the global '.op-page'
         / '.op-page.wide' selectors, so it wins in both fixed and wide modes
         and affects ONLY this page. The canvas (minmax(0,1fr)) then keeps the
         freed width with the dock beside it. */
      .op-page.pl-page {
        max-width: min(96vw, 1600px);
      }
      .pl-grid {
        display: grid;
        grid-template-columns: minmax(0, 1fr) auto;
        gap: 1rem;
        align-items: start;
      }
      /* Collapsed: the drawer folds away and the canvas takes the whole row. */
      .pl-grid.collapsed {
        grid-template-columns: minmax(0, 1fr) auto;
        gap: 0.5rem;
      }
      .col {
        display: flex;
        flex-direction: column;
        gap: 0.75rem;
        min-width: 0;
      }
      /* Sticky right dock = a slim toggle rail + (when open) the drawer. */
      .dock {
        position: sticky;
        top: 1rem;
        display: flex;
        align-items: flex-start;
        gap: 0;
      }
      .rail-toggle {
        flex: 0 0 auto;
        display: inline-flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        gap: 0.5rem;
        width: 30px;
        min-height: 96px;
        padding: 0.75rem 0;
        border: 1px solid var(--border);
        border-radius: 10px 0 0 10px;
        background: var(--surface);
        color: var(--text-muted);
        cursor: pointer;
        transition: background 0.15s ease, color 0.15s ease;
      }
      .rail-toggle:hover {
        color: var(--accent);
        border-color: var(--accent);
      }
      .rail-toggle:focus-visible {
        outline: 2px solid var(--accent);
        outline-offset: 2px;
      }
      /* When the drawer is hidden the rail stands alone — round all corners. */
      .pl-grid.collapsed .rail-toggle {
        border-radius: 10px;
      }
      .rail-chevron {
        font-size: 1.05rem;
        line-height: 1;
        font-weight: 700;
      }
      .rail-label {
        writing-mode: vertical-rl;
        text-orientation: mixed;
        font-size: 0.72rem;
        letter-spacing: 0.04em;
      }
      .side {
        width: 300px;
        gap: 1rem;
      }
      .card-h {
        margin: 0 0 0.75rem;
        font-size: 1.05rem;
      }
      /* Palette card = a header toolbar (label + file actions) above the
         grouped node chips, separated by a hairline for clear hierarchy. */
      .palette-card {
        display: flex;
        flex-direction: column;
        gap: 1rem;
      }
      .pl-toolbar {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        justify-content: space-between;
        gap: 0.5rem 1rem;
        padding-bottom: 0.85rem;
        border-bottom: 1px solid var(--border);
      }
      .pl-toolbar-h {
        font-size: 0.95rem;
        font-weight: 700;
        color: var(--text);
      }
      /* File actions (Save / Load / Clear) — a distinct right-aligned cluster. */
      .file-actions {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 0.35rem;
        margin-left: auto;
      }
      /* The node palette: kind groups laid out FlowPane-style, wrapping freely. */
      .palette-groups {
        display: flex;
        flex-wrap: wrap;
        gap: 1rem 1.5rem;
        align-items: flex-start;
      }
      .palette-group {
        display: flex;
        flex-direction: column;
        gap: 0.45rem;
        min-width: 0;
      }
      .group-label {
        font-size: 0.7rem;
        font-weight: 700;
        letter-spacing: 0.06em;
        text-transform: uppercase;
        color: var(--text-muted);
      }
      .chips {
        display: flex;
        flex-wrap: wrap;
        gap: 0.4rem;
      }
      .chip {
        display: inline-flex;
        align-items: center;
        gap: 0.35rem;
        padding: 0.4rem 0.7rem;
        font-size: 0.82rem;
      }
      .chip-ico {
        width: 0.95rem;
        height: 0.95rem;
        flex-shrink: 0;
      }
      .canvas-help {
        margin: 0;
      }
      .verr {
        margin: 0.75rem 0 0;
        padding-left: 1.1rem;
        color: var(--danger);
        font-size: 0.85rem;
      }
      .verr-line {
        color: var(--danger);
        font-size: 0.85rem;
      }
      .ok-note {
        color: var(--success);
        font-size: 0.9rem;
      }
      .done-box {
        margin-top: 0.75rem;
        border: 1px solid var(--success);
        border-radius: 8px;
        padding: 0.75rem;
      }
      .filename {
        font-weight: 600;
        margin: 0 0 0.5rem;
        word-break: break-all;
      }
      /* Advisory note is desktop-hidden; only surfaces on small viewports. */
      .mobile-hint {
        display: none;
      }
      @media (max-width: 768px) {
        .pl-grid,
        .pl-grid.collapsed {
          grid-template-columns: 1fr;
        }
        .dock {
          position: static;
          flex-direction: column;
          align-items: stretch;
        }
        /* On narrow screens the rail becomes a full-width horizontal bar. */
        .rail-toggle {
          flex-direction: row;
          width: auto;
          min-height: 0;
          padding: 0.5rem 0.75rem;
          border-radius: 10px;
        }
        .pl-grid.collapsed .rail-toggle {
          border-radius: 10px;
        }
        .rail-label {
          writing-mode: horizontal-tb;
        }
        .side {
          width: auto;
        }
        .mobile-hint {
          display: block;
        }
      }
    `,
  ],
})
export class PipelinePage implements OnInit, AfterViewInit {
  private readonly transloco = inject(TranslocoService);
  private readonly api = inject(ApiService);

  @ViewChild('cv') private canvas!: PipelineCanvasComponent;

  protected readonly kinds = signal<NodeKindInfo[]>(FALLBACK_KINDS);

  /**
   * Palette chips grouped by node kind for a clear source → transform → combine
   * → export hierarchy. SOURCE has its own dedicated group in the template; here
   * we partition the remaining op kinds. Empty groups are dropped.
   */
  protected readonly groups = computed(() => {
    const ks = this.kinds();
    const transform = ks.filter((k) => !k.isReduce && !k.isExport);
    const combine = ks.filter((k) => k.isReduce);
    const exportKinds = ks.filter((k) => k.isExport);
    // TODO(i18n): localize palette group labels "Transform" / "Combine" / "Export".
    const out: { key: string; label: string; kinds: NodeKindInfo[] }[] = [];
    if (transform.length) out.push({ key: 'transform', label: 'Transform', kinds: transform });
    if (combine.length) out.push({ key: 'combine', label: 'Combine', kinds: combine });
    if (exportKinds.length) out.push({ key: 'export', label: 'Export', kinds: exportKinds });
    return out;
  });

  /** Inspector/Run drawer open state — remembered across visits so the canvas can stay wide. */
  protected readonly panelOpen = signal(PipelinePage.readPanelOpen());

  protected readonly busy = signal(false);
  protected readonly error = signal<ApiError | null>(null);
  protected readonly result = signal<RunResult | null>(null);
  protected readonly validationErrors = signal<PipelineValidationError[] | null>(null);

  ngOnInit(): void {
    // Best-effort: use the backend's node-kind catalog; fall back if absent.
    this.api.getPipelineKinds().subscribe({
      next: (ks) => {
        // SOURCE has its own dedicated palette chip; drop it from the op list.
        if (ks?.length) this.kinds.set(ks.filter((k) => !k.isSource));
      },
      error: () => {
        /* keep FALLBACK_KINDS */
      },
    });
  }

  ngAfterViewInit(): void {
    // Seed one SOURCE node so the surface is never empty on first visit, and leave it selected so
    // the inspector immediately shows its per-node upload drop zone.
    if (!this.canvas.hasSource()) {
      this.canvas.addNode('SOURCE');
    }
  }

  private static readonly PANEL_KEY = 'pl.panelOpen';

  /** Restore the last drawer state (defaults to open on first visit / unavailable storage). */
  private static readPanelOpen(): boolean {
    try {
      return localStorage.getItem(PipelinePage.PANEL_KEY) !== '0';
    } catch {
      return true;
    }
  }

  /** Toggle the inspector/run drawer and persist the choice. */
  togglePanel(): void {
    const next = !this.panelOpen();
    this.panelOpen.set(next);
    try {
      localStorage.setItem(PipelinePage.PANEL_KEY, next ? '1' : '0');
    } catch {
      /* storage unavailable — state still lives in the signal for this session */
    }
  }

  /** Glyph registry key for a node kind's palette chip. */
  iconFor(kind: NodeKindName): string {
    return KIND_TO_OP[kind] ?? 'source';
  }

  /** Translated display label for a node kind. */
  nodeLabel(kind: NodeKindName): string {
    const opId = KIND_TO_OP[kind];
    if (opId) return this.transloco.translate(`op.${opId}.label`);
    return this.kinds().find((k) => k.name === kind)?.label ?? kind;
  }

  validate(cv: PipelineCanvasComponent): void {
    this.busy.set(true);
    this.resetOutcome();
    this.api.validatePipeline(cv.toModel()).subscribe({
      next: (errs) => {
        this.validationErrors.set(errs ?? []);
        this.busy.set(false);
      },
      error: (e) => {
        this.error.set(e instanceof ApiError ? e : new ApiError('unknown', String(e), 0));
        this.busy.set(false);
      },
    });
  }

  run(cv: PipelineCanvasComponent): void {
    if (!cv.runnable()) return;
    this.busy.set(true);
    this.resetOutcome();
    const fd = new FormData();
    fd.append('pipeline', JSON.stringify(cv.toModel()));
    // Each SOURCE node owns its uploads; gather them all (deduped by name) for the multipart request.
    for (const f of cv.allSourceFiles()) fd.append('files', f, f.name);
    for (const a of cv.assetFiles()) fd.append('nodeAssets', a, a.name);
    this.api.runPipeline(fd).subscribe({
      next: (r) => {
        this.result.set(r);
        this.busy.set(false);
      },
      error: (e) => {
        this.error.set(e instanceof ApiError ? e : new ApiError('unknown', String(e), 0));
        this.busy.set(false);
      },
    });
  }

  save(): void {
    const blob = new Blob([JSON.stringify(this.canvas.toModel(), null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'pipeline.json';
    a.click();
    URL.revokeObjectURL(url);
  }

  load(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    file
      .text()
      .then((text) => {
        this.canvas.loadModel(JSON.parse(text));
        this.resetOutcome();
      })
      .catch(() => this.error.set(new ApiError('bad_request', this.transloco.translate('pipeline.canvas.loadError'), 0)));
  }

  download(): void {
    if (this.result()) downloadRunResult(this.result()!);
  }

  private resetOutcome(): void {
    this.error.set(null);
    this.result.set(null);
    this.validationErrors.set(null);
  }
}
