import { Component, OnInit, computed, signal } from '@angular/core';

import { ApiError, RunResult } from '../../core/api.models';
import { ApiService } from '../../core/api.service';
import { downloadRunResult } from '../../core/download.util';
import {
  ConnectionJson,
  ImageFormatName,
  NodeKindInfo,
  NodeKindName,
  PageSizeName,
  PipelineModelJson,
  PipelineNodeJson,
  PipelineValidationError,
  SplitModeName,
  TextFormatName,
} from '../../core/pipeline.models';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
import { SpinnerComponent } from '../../shared/spinner/spinner.component';

/** A node being edited in the builder. Holds every possible param; only the
 *  fields relevant to its kind are emitted into the pipeline JSON. */
interface BuilderNode {
  id: string;
  kind: NodeKindName;
  /** Upstream node ids feeding this node (become `connections`). */
  inputs: string[];
  pages: string;
  splitMode: SplitModeName;
  order: string;
  angle: number;
  targetSize: string;
  pageSize: PageSizeName;
  password: string;
  ownerPassword: string;
  metaTitle: string;
  metaAuthor: string;
  metaSubject: string;
  metaKeywords: string;
  metaStrip: boolean;
  wmText: string;
  wmOpacity: number;
  wmRotation: number;
  wmScale: number;
  imageFormat: ImageFormatName;
  imageDpi: number;
  textFormat: TextFormatName;
}

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

const SOURCE_ID = 'source';

@Component({
  selector: 'app-pipeline-page',
  standalone: true,
  imports: [FileDropZoneComponent, PageHeaderComponent, SpinnerComponent],
  template: `
    <section class="op-page wide">
      <app-page-header title="Pipeline" description="Chain operations as a node graph." />

      <div class="pl-grid">
        <div class="col">
          <!-- Source -->
          <div class="card">
            <h2 class="card-h">Source files</h2>
            <app-file-drop-zone
              [multiple]="true"
              accept=".pdf,image/*,.docx,.odt,.rtf,.txt,.xlsx,.pptx"
              hint="These feed the SOURCE node; downstream nodes reference them by name."
              (filesChange)="files.set($event)"
            />
          </div>

          <!-- Nodes -->
          <div class="card">
            <div class="card-h-row">
              <h2 class="card-h">Nodes</h2>
              <span class="hint-note">{{ nodes().length }} operation node(s)</span>
            </div>

            <div class="node source-node">
              <div class="node-head">
                <span class="badge">SOURCE</span>
                <span class="node-title">Files ({{ files().length }})</span>
              </div>
              @if (files().length) {
                <p class="hint-note file-names">{{ fileNames() }}</p>
              } @else {
                <p class="hint-note">Add source files above.</p>
              }
            </div>

            @for (n of nodes(); track n.id; let i = $index) {
              <div class="node">
                <div class="node-head">
                  <span class="badge op">{{ labelFor(n.kind) }}</span>
                  <span class="node-id">{{ n.id }}</span>
                  <button type="button" class="icon-btn" (click)="removeNode(n.id)" aria-label="Remove node">✕</button>
                </div>

                <div class="field">
                  <span class="field-label">Inputs</span>
                  <div class="inputs">
                    @for (opt of upstreamOptions(n); track opt.id) {
                      <label class="check">
                        <input
                          type="checkbox"
                          [checked]="n.inputs.includes(opt.id)"
                          (change)="toggleInput(n, opt.id, $any($event.target).checked)"
                        />
                        {{ opt.label }}
                      </label>
                    }
                  </div>
                </div>

                <!-- Per-kind params -->
                @switch (n.kind) {
                  @case ('EXTRACT') {
                    <div class="form-grid">
                      <div class="field"><label>Pages</label>
                        <input type="text" [value]="n.pages" (input)="patch(n,'pages',$any($event.target).value)" placeholder="1,3,5-8" /></div>
                      <div class="field"><label>Mode</label>
                        <select [value]="n.splitMode" (change)="patch(n,'splitMode',$any($event.target).value)">
                          <option value="COMBINE">Combine</option>
                          <option value="SEPARATE">Separate files</option>
                        </select></div>
                    </div>
                  }
                  @case ('ROTATE') {
                    <div class="form-grid">
                      <div class="field"><label>Pages</label>
                        <input type="text" [value]="n.pages" (input)="patch(n,'pages',$any($event.target).value)" placeholder="blank = all" /></div>
                      <div class="field"><label>Angle</label>
                        <select [value]="n.angle" (change)="patch(n,'angle',+$any($event.target).value)">
                          <option [value]="90">90°</option><option [value]="180">180°</option><option [value]="270">270°</option>
                        </select></div>
                    </div>
                  }
                  @case ('ARRANGE') {
                    <div class="field"><label>Order</label>
                      <input type="text" [value]="n.order" (input)="patch(n,'order',$any($event.target).value)" placeholder="3,1,2" /></div>
                  }
                  @case ('COMPRESS') {
                    <div class="field"><label>Target size</label>
                      <input type="text" [value]="n.targetSize" (input)="patch(n,'targetSize',$any($event.target).value)" placeholder="5MB" /></div>
                  }
                  @case ('IMAGES_TO_PDF') {
                    <div class="field"><label>Page size</label>
                      <select [value]="n.pageSize" (change)="patch(n,'pageSize',$any($event.target).value)">
                        <option value="FIT">Fit</option><option value="A4">A4</option><option value="A3">A3</option><option value="LETTER">Letter</option>
                      </select></div>
                  }
                  @case ('PROTECT') {
                    <div class="form-grid">
                      <div class="field"><label>User password</label>
                        <input type="password" [value]="n.password" (input)="patch(n,'password',$any($event.target).value)" /></div>
                      <div class="field"><label>Owner password</label>
                        <input type="password" [value]="n.ownerPassword" (input)="patch(n,'ownerPassword',$any($event.target).value)" /></div>
                    </div>
                  }
                  @case ('UNLOCK') {
                    <div class="field"><label>Password</label>
                      <input type="password" [value]="n.password" (input)="patch(n,'password',$any($event.target).value)" /></div>
                  }
                  @case ('METADATA') {
                    <div class="form-grid">
                      <div class="field"><label>Title</label><input type="text" [value]="n.metaTitle" (input)="patch(n,'metaTitle',$any($event.target).value)" /></div>
                      <div class="field"><label>Author</label><input type="text" [value]="n.metaAuthor" (input)="patch(n,'metaAuthor',$any($event.target).value)" /></div>
                      <div class="field"><label>Subject</label><input type="text" [value]="n.metaSubject" (input)="patch(n,'metaSubject',$any($event.target).value)" /></div>
                      <div class="field"><label>Keywords</label><input type="text" [value]="n.metaKeywords" (input)="patch(n,'metaKeywords',$any($event.target).value)" /></div>
                      <div class="field full"><label class="check"><input type="checkbox" [checked]="n.metaStrip" (change)="patch(n,'metaStrip',$any($event.target).checked)" /> Strip all metadata</label></div>
                    </div>
                  }
                  @case ('WATERMARK') {
                    <div class="form-grid">
                      <div class="field full"><label>Text</label><input type="text" [value]="n.wmText" (input)="patch(n,'wmText',$any($event.target).value)" placeholder="CONFIDENTIAL" /></div>
                      <div class="field"><label>Opacity</label><input type="number" min="0.05" max="1" step="0.05" [value]="n.wmOpacity" (input)="patch(n,'wmOpacity',+$any($event.target).value)" /></div>
                      <div class="field"><label>Rotation</label><input type="number" min="0" max="360" step="5" [value]="n.wmRotation" (input)="patch(n,'wmRotation',+$any($event.target).value)" /></div>
                      <div class="field"><label>Scale</label><input type="number" min="0.1" max="1" step="0.05" [value]="n.wmScale" (input)="patch(n,'wmScale',+$any($event.target).value)" /></div>
                    </div>
                  }
                  @case ('TO_IMAGES') {
                    <div class="form-grid">
                      <div class="field"><label>Format</label>
                        <select [value]="n.imageFormat" (change)="patch(n,'imageFormat',$any($event.target).value)">
                          <option value="PNG">PNG</option><option value="JPEG">JPG</option>
                        </select></div>
                      <div class="field"><label>DPI</label><input type="number" min="36" max="600" [value]="n.imageDpi" (input)="patch(n,'imageDpi',+$any($event.target).value)" /></div>
                    </div>
                  }
                  @case ('TO_TEXT') {
                    <div class="field"><label>Format</label>
                      <select [value]="n.textFormat" (change)="patch(n,'textFormat',$any($event.target).value)">
                        <option value="TXT">TXT</option>
                      </select></div>
                  }
                }
              </div>
            }

            <div class="palette">
              <span class="field-label">Add node:</span>
              @for (k of kinds(); track k.name) {
                <button type="button" class="btn" (click)="addNode(k.name)">+ {{ k.label }}</button>
              }
            </div>
          </div>
        </div>

        <!-- Right column: actions + JSON + results -->
        <aside class="col side">
          <div class="card">
            <div class="btn-row">
              <button type="button" class="btn" [disabled]="busy()" (click)="validate()">Validate</button>
              <button type="button" class="btn btn-primary" [disabled]="!canRun() || busy()" (click)="run()">Run pipeline</button>
            </div>
            @if (busy()) { <app-spinner label="Running…" /> }

            @if (validationErrors() !== null) {
              @if (validationErrors()!.length) {
                <ul class="verr">
                  @for (e of validationErrors()!; track $index) {
                    <li>{{ e.nodeId ? '[' + e.nodeId + '] ' : '' }}{{ e.message }}</li>
                  }
                </ul>
              } @else {
                <p class="ok-note">✓ Pipeline is valid.</p>
              }
            }
            @if (error()) { <p class="verr-line">{{ error()!.message }}</p> }
            @if (result()) {
              <div class="done-box">
                <p class="filename">{{ result()!.filename }}</p>
                <button type="button" class="btn btn-primary" (click)="download()">Download ZIP</button>
              </div>
            }
          </div>

          <div class="card">
            <div class="card-h-row">
              <h2 class="card-h">Pipeline JSON</h2>
              <button type="button" class="btn btn-ghost" (click)="showJson.set(!showJson())">
                {{ showJson() ? 'Hide' : 'Show' }}
              </button>
            </div>
            @if (showJson()) {
              <pre class="json">{{ modelJson() }}</pre>
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
        gap: 1.25rem;
        min-width: 0;
      }
      .side {
        position: sticky;
        top: 1rem;
      }
      .card-h {
        margin: 0;
        font-size: 1.05rem;
      }
      .card-h-row {
        display: flex;
        align-items: center;
        justify-content: space-between;
        margin-bottom: 0.75rem;
      }
      .node {
        border: 1px solid var(--border);
        border-radius: var(--radius);
        padding: 0.85rem;
        margin-top: 0.75rem;
        background: var(--surface-2);
        display: flex;
        flex-direction: column;
        gap: 0.7rem;
      }
      .source-node {
        border-style: dashed;
      }
      .node-head {
        display: flex;
        align-items: center;
        gap: 0.5rem;
      }
      .badge {
        font-size: 0.7rem;
        font-weight: 700;
        letter-spacing: 0.03em;
        padding: 0.15rem 0.45rem;
        border-radius: 5px;
        background: var(--border-strong);
        color: var(--text);
      }
      .badge.op {
        background: var(--accent-soft);
        color: var(--accent);
      }
      .node-id,
      .node-title {
        font-size: 0.85rem;
        color: var(--text-muted);
      }
      .node-head .icon-btn {
        margin-left: auto;
      }
      .inputs {
        display: flex;
        flex-wrap: wrap;
        gap: 0.5rem 1rem;
      }
      .file-names {
        word-break: break-all;
      }
      .palette {
        display: flex;
        flex-wrap: wrap;
        gap: 0.5rem;
        align-items: center;
        margin-top: 1rem;
        padding-top: 0.85rem;
        border-top: 1px solid var(--border);
      }
      .json {
        margin: 0;
        max-height: 340px;
        overflow: auto;
        font-family: var(--font-mono);
        font-size: 0.72rem;
        background: var(--surface-2);
        padding: 0.75rem;
        border-radius: 8px;
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
export class PipelinePage implements OnInit {
  protected readonly files = signal<File[]>([]);
  protected readonly nodes = signal<BuilderNode[]>([]);
  protected readonly kinds = signal<NodeKindInfo[]>(FALLBACK_KINDS);

  protected readonly busy = signal(false);
  protected readonly error = signal<ApiError | null>(null);
  protected readonly result = signal<RunResult | null>(null);
  protected readonly validationErrors = signal<PipelineValidationError[] | null>(null);
  protected readonly showJson = signal(false);

  protected readonly fileNames = computed(() => this.files().map((f) => f.name).join(', '));
  protected readonly modelJson = computed(() => JSON.stringify(this.buildModel(), null, 2));

  private seq = 0;

  constructor(private readonly api: ApiService) {}

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

  labelFor(kind: NodeKindName): string {
    return this.kinds().find((k) => k.name === kind)?.label ?? kind;
  }

  /** Nodes that may feed `n`: the source plus every other node (backend detects cycles). */
  upstreamOptions(n: BuilderNode): { id: string; label: string }[] {
    const opts = [{ id: SOURCE_ID, label: 'SOURCE (files)' }];
    for (const other of this.nodes()) {
      if (other.id !== n.id) opts.push({ id: other.id, label: `${other.id} · ${this.labelFor(other.kind)}` });
    }
    return opts;
  }

  addNode(kind: NodeKindName): void {
    const id = `${kind.toLowerCase()}_${++this.seq}`;
    const last = this.nodes()[this.nodes().length - 1];
    const node: BuilderNode = {
      id,
      kind,
      inputs: [last ? last.id : SOURCE_ID],
      pages: '',
      splitMode: 'COMBINE',
      order: '',
      angle: 90,
      targetSize: '5MB',
      pageSize: 'FIT',
      password: '',
      ownerPassword: '',
      metaTitle: '',
      metaAuthor: '',
      metaSubject: '',
      metaKeywords: '',
      metaStrip: false,
      wmText: '',
      wmOpacity: 0.3,
      wmRotation: 45,
      wmScale: 0.5,
      imageFormat: 'PNG',
      imageDpi: 150,
      textFormat: 'TXT',
    };
    this.nodes.set([...this.nodes(), node]);
    this.resetOutcome();
  }

  removeNode(id: string): void {
    this.nodes.set(
      this.nodes()
        .filter((n) => n.id !== id)
        .map((n) => ({ ...n, inputs: n.inputs.filter((i) => i !== id) })),
    );
    this.resetOutcome();
  }

  toggleInput(node: BuilderNode, inputId: string, on: boolean): void {
    const inputs = on ? [...node.inputs, inputId] : node.inputs.filter((i) => i !== inputId);
    this.patch(node, 'inputs', inputs);
  }

  patch<K extends keyof BuilderNode>(node: BuilderNode, key: K, value: BuilderNode[K]): void {
    this.nodes.set(this.nodes().map((n) => (n.id === node.id ? { ...n, [key]: value } : n)));
    this.resetOutcome();
  }

  canRun(): boolean {
    return this.files().length > 0 && this.nodes().length > 0;
  }

  validate(): void {
    this.busy.set(true);
    this.resetOutcome();
    this.api.validatePipeline(this.buildModel()).subscribe({
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

  run(): void {
    if (!this.canRun()) return;
    this.busy.set(true);
    this.resetOutcome();
    const fd = new FormData();
    fd.append('pipeline', JSON.stringify(this.buildModel()));
    for (const f of this.files()) fd.append('files', f, f.name);
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

  download(): void {
    if (this.result()) downloadRunResult(this.result()!);
  }

  private resetOutcome(): void {
    this.error.set(null);
    this.result.set(null);
    this.validationErrors.set(null);
  }

  /** Assemble the PipelineModel JSON matching the backend field names exactly. */
  private buildModel(): PipelineModelJson {
    const source: PipelineNodeJson = {
      id: SOURCE_ID,
      kind: 'SOURCE',
      x: 40,
      y: 40,
      files: this.files().map((f) => f.name),
    };

    const connections: ConnectionJson[] = [];
    const nodeJson: PipelineNodeJson[] = this.nodes().map((n, idx) => {
      for (const from of n.inputs) connections.push({ fromNodeId: from, toNodeId: n.id });
      return this.nodeToJson(n, idx);
    });

    return { nodes: [source, ...nodeJson], connections };
  }

  private nodeToJson(n: BuilderNode, idx: number): PipelineNodeJson {
    const base: PipelineNodeJson = { id: n.id, kind: n.kind, x: 40 + (idx + 1) * 220, y: 40 };
    switch (n.kind) {
      case 'EXTRACT':
        return { ...base, pages: n.pages, splitMode: n.splitMode };
      case 'ROTATE':
        return { ...base, pages: n.pages, angle: n.angle };
      case 'ARRANGE':
        return { ...base, order: n.order };
      case 'COMPRESS':
        return { ...base, targetBytes: parseSize(n.targetSize) };
      case 'IMAGES_TO_PDF':
        return { ...base, pageSize: n.pageSize };
      case 'PROTECT':
        return { ...base, password: n.password, ownerPassword: n.ownerPassword };
      case 'UNLOCK':
        return { ...base, password: n.password };
      case 'METADATA':
        return {
          ...base,
          metaTitle: n.metaTitle,
          metaAuthor: n.metaAuthor,
          metaSubject: n.metaSubject,
          metaKeywords: n.metaKeywords,
          metaStrip: n.metaStrip,
        };
      case 'WATERMARK':
        return { ...base, wmText: n.wmText, wmOpacity: n.wmOpacity, wmRotation: n.wmRotation, wmScale: n.wmScale };
      case 'TO_IMAGES':
        return { ...base, imageFormat: n.imageFormat, imageDpi: n.imageDpi };
      case 'TO_TEXT':
        return { ...base, textFormat: n.textFormat };
      default:
        return base; // MERGE has no params
    }
  }
}

/** Parse "5MB"/"800KB"/"1234" into bytes (mirrors the CLI SizeConverter). */
function parseSize(text: string): number {
  const m = /^\s*(\d+(?:\.\d+)?)\s*(b|kb|mb|gb)?\s*$/i.exec(text ?? '');
  if (!m) return 5 * 1024 * 1024;
  const value = parseFloat(m[1]);
  const unit = (m[2] ?? 'b').toLowerCase();
  const factor = unit === 'gb' ? 1024 ** 3 : unit === 'mb' ? 1024 ** 2 : unit === 'kb' ? 1024 : 1;
  return Math.round(value * factor);
}
