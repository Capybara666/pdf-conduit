import { AfterViewInit, Component, OnInit, ViewChild, inject, signal } from '@angular/core';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';

import { ApiError, RunResult } from '../../core/api.models';
import { ApiService } from '../../core/api.service';
import { downloadRunResult } from '../../core/download.util';
import { NodeKindInfo, NodeKindName, PipelineValidationError } from '../../core/pipeline.models';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
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
  { name: 'TO_IMAGES', label: 'To Images', isSource: false, isReduce: false, isExport: true },
  { name: 'TO_TEXT', label: 'To Text', isSource: false, isReduce: false, isExport: true },
];

/** NodeKind → operation id used for i18n label lookup (`op.<id>.label`). */
const KIND_TO_OP: Record<string, string> = {
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
    SpinnerComponent,
    PipelineCanvasComponent,
    PipelineInspectorComponent,
  ],
  template: `
    <section class="op-page wide">
      <app-page-header
        [title]="'pages.pipeline.title' | transloco"
        [description]="'pages.pipeline.description' | transloco"
      />

      <!-- Small-viewport advisory: the node canvas is pointer-oriented and reads best on a wider screen. -->
      <p class="hint-note mobile-hint">{{ 'pipeline.mobileHint' | transloco }}</p>

      <!-- Palette + toolbar -->
      <div class="card">
        <div class="palette">
          <span class="field-label">{{ 'pipeline.canvas.addNode' | transloco }}</span>
          <button type="button" class="btn chip" (click)="cv.addNode('SOURCE')">+ {{ 'pipeline.canvas.source' | transloco }}</button>
          @for (k of kinds(); track k.name) {
            <button type="button" class="btn chip" (click)="cv.addNode(k.name)">+ {{ nodeLabel(k.name) }}</button>
          }
          <span class="tb-spacer"></span>
          <button type="button" class="btn btn-ghost" (click)="save()" [disabled]="!cv.nodes().length">{{ 'pipeline.canvas.save' | transloco }}</button>
          <button type="button" class="btn btn-ghost" (click)="loadInput.click()">{{ 'pipeline.canvas.load' | transloco }}</button>
          <button type="button" class="btn btn-ghost" (click)="cv.clear()" [disabled]="!cv.nodes().length">{{ 'pipeline.canvas.clear' | transloco }}</button>
          <input #loadInput type="file" accept="application/json,.json" hidden (change)="load($event)" />
        </div>
      </div>

      <div class="pl-grid">
        <!-- Canvas -->
        <div class="col">
          <app-pipeline-canvas #cv />
          <p class="hint-note canvas-help">{{ 'pipeline.canvas.help' | transloco }}</p>
        </div>

        <!-- Right column -->
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
      </div>
    </section>
  `,
  styles: [
    `
      .pl-grid {
        display: grid;
        grid-template-columns: minmax(0, 1fr) 320px;
        gap: 1.25rem;
        align-items: start;
      }
      .col {
        display: flex;
        flex-direction: column;
        gap: 0.75rem;
        min-width: 0;
      }
      .side {
        position: sticky;
        top: 1rem;
        gap: 1.25rem;
      }
      .card-h {
        margin: 0 0 0.75rem;
        font-size: 1.05rem;
      }
      .palette {
        display: flex;
        flex-wrap: wrap;
        gap: 0.5rem;
        align-items: center;
      }
      .chip {
        padding: 0.4rem 0.7rem;
        font-size: 0.82rem;
      }
      .tb-spacer {
        flex: 1;
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
        .pl-grid {
          grid-template-columns: 1fr;
        }
        .side {
          position: static;
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
