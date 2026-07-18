import { AfterViewInit, Component, OnInit, ViewChild, computed, inject, signal } from '@angular/core';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';

import { ApiError, RunResult } from '../../core/api.models';
import { ApiService } from '../../core/api.service';
import { downloadRunResult } from '../../core/download.util';
import { NodeKindInfo, NodeKindName, PipelineValidationError } from '../../core/pipeline.models';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
import { SpinnerComponent } from '../../shared/spinner/spinner.component';
import { PipelineCanvasComponent } from './canvas/pipeline-canvas.component';
import { PipelineInspectorComponent } from './canvas/pipeline-inspector.component';

/** Palette fallback if `GET /api/pipeline/kinds` is unavailable. Names match NodeKind. */
const FALLBACK_KINDS: NodeKindInfo[] = [
  { name: 'MERGE', label: 'Merge', cardinality: 'REDUCE' },
  { name: 'IMAGES_TO_PDF', label: 'To PDF', cardinality: 'MAP' },
  { name: 'EXTRACT', label: 'Extract', cardinality: 'MAP' },
  { name: 'COMPRESS', label: 'Compress', cardinality: 'MAP' },
  { name: 'ROTATE', label: 'Rotate', cardinality: 'MAP' },
  { name: 'ARRANGE', label: 'Arrange', cardinality: 'MAP' },
  { name: 'PROTECT', label: 'Protect', cardinality: 'MAP' },
  { name: 'UNLOCK', label: 'Unlock', cardinality: 'MAP' },
  { name: 'METADATA', label: 'Metadata', cardinality: 'MAP' },
  { name: 'WATERMARK', label: 'Watermark', cardinality: 'MAP' },
  { name: 'TO_IMAGES', label: 'To Images', cardinality: 'MAP', export: true },
  { name: 'TO_TEXT', label: 'To Text', cardinality: 'MAP', export: true },
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
    FileDropZoneComponent,
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
          <app-pipeline-canvas #cv [poolNames]="fileNames()" />
          <p class="hint-note canvas-help">{{ 'pipeline.canvas.help' | transloco }}</p>
        </div>

        <!-- Right column -->
        <aside class="col side">
          <div class="card">
            <h2 class="card-h">{{ 'pipeline.canvas.sourceTitle' | transloco }}</h2>
            <app-file-drop-zone
              [multiple]="true"
              accept=".pdf,image/*,.docx,.odt,.rtf,.txt,.xlsx,.pptx"
              [hint]="'pages.pipeline.sourceHint' | transloco"
              (filesChange)="onFiles($event)"
            />
          </div>

          <div class="card">
            <h2 class="card-h">{{ 'pipeline.canvas.inspector' | transloco }}</h2>
            <app-pipeline-inspector
              [node]="cv.selectedNode()"
              [pool]="fileNames()"
              (patch)="cv.patchSelected($event)"
              (asset)="cv.setSelectedAsset($event)"
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
      @media (max-width: 900px) {
        .pl-grid {
          grid-template-columns: 1fr;
        }
        .side {
          position: static;
        }
      }
    `,
  ],
})
export class PipelinePage implements OnInit, AfterViewInit {
  private readonly transloco = inject(TranslocoService);
  private readonly api = inject(ApiService);

  @ViewChild('cv') private canvas!: PipelineCanvasComponent;

  protected readonly files = signal<File[]>([]);
  protected readonly kinds = signal<NodeKindInfo[]>(FALLBACK_KINDS);

  protected readonly busy = signal(false);
  protected readonly error = signal<ApiError | null>(null);
  protected readonly result = signal<RunResult | null>(null);
  protected readonly validationErrors = signal<PipelineValidationError[] | null>(null);

  protected readonly fileNames = computed(() => this.files().map((f) => f.name));

  ngOnInit(): void {
    // Best-effort: use the backend's node-kind catalog; fall back if absent.
    this.api.getPipelineKinds().subscribe({
      next: (ks) => {
        if (ks?.length) this.kinds.set(ks.filter((k) => k.name !== 'SOURCE'));
      },
      error: () => {
        /* keep FALLBACK_KINDS */
      },
    });
  }

  ngAfterViewInit(): void {
    // Seed one SOURCE node so the surface is never empty on first visit.
    if (!this.canvas.hasSource()) {
      this.canvas.addNode('SOURCE');
      this.canvas.selectedId.set(null);
    }
  }

  /** Translated display label for a node kind. */
  nodeLabel(kind: NodeKindName): string {
    const opId = KIND_TO_OP[kind];
    if (opId) return this.transloco.translate(`op.${opId}.label`);
    return this.kinds().find((k) => k.name === kind)?.label ?? kind;
  }

  onFiles(files: File[]): void {
    this.files.set(files);
    // Feed the uploaded basenames into any not-yet-configured SOURCE node.
    this.canvas.poolNames = this.fileNames();
    this.canvas.syncEmptySources();
    this.resetOutcome();
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
    for (const f of this.files()) fd.append('files', f, f.name);
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
