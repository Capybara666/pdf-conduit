import {
  Component,
  ElementRef,
  HostListener,
  ViewChild,
  computed,
  signal,
} from '@angular/core';

import {
  CanvasNode,
  ConnectionJson,
  NodeKindName,
  PipelineModelJson,
  fromWireNode,
  newCanvasNode,
  toWireNode,
} from '../../../core/pipeline.models';
import { PipelineNodeComponent } from './pipeline-node.component';
import { PipelineConnectionComponent } from './pipeline-connection.component';
import {
  CARD_W,
  PORT_HIT_RADIUS,
  Point,
  inPortCenter,
  outPortCenter,
  wirePath,
} from './pipeline-geometry';

interface Wire {
  conn: ConnectionJson;
  from: Point;
  to: Point;
}

/**
 * The free-form pipeline editing surface. Owns the graph (nodes + connections),
 * selection and id minting; hosts draggable node cards over an SVG wire overlay.
 *
 * Connections are drawn from a node's OUTPUT port; on release the nearest INPUT
 * port within {@link PORT_HIT_RADIUS} completes the edge, subject to
 * {@link canConnect} (no self-loop, no target=SOURCE, no duplicate, no cycle).
 * Delete/Backspace removes the selected node (ignored while typing in a field).
 */
@Component({
  selector: 'app-pipeline-canvas',
  standalone: true,
  imports: [PipelineNodeComponent, PipelineConnectionComponent],
  template: `
    <div
      #surface
      class="pl-surface"
      tabindex="0"
      role="application"
      (pointerdown)="onSurfaceDown($event)"
    >
      <div
        #content
        class="pl-content"
        [style.width.px]="contentSize().w"
        [style.height.px]="contentSize().h"
      >
        <svg class="pl-wires" [attr.width]="contentSize().w" [attr.height]="contentSize().h">
          @for (w of wires(); track w.conn.fromNodeId + '>' + w.conn.toNodeId) {
            <svg:g
              app-pipeline-connection
              [from]="w.from"
              [to]="w.to"
              (remove)="removeConnection(w.conn)"
            ></svg:g>
          }
          @if (tempWire(); as tw) {
            <svg:path
              class="pl-temp"
              [class.valid]="hoverValid()"
              [class.invalid]="hoverTargetId() !== null && !hoverValid()"
              [attr.d]="tw"
            />
          }
        </svg>

        @for (n of nodes(); track n.id) {
          <app-pipeline-node
            [node]="n"
            [selected]="n.id === selectedId()"
            [inValid]="n.id === hoverTargetId() && hoverValid()"
            [inInvalid]="n.id === hoverTargetId() && !hoverValid()"
            [inConnected]="inConnectedIds().has(n.id)"
            [outConnected]="outConnectedIds().has(n.id)"
            [outActive]="pendingFrom() === n.id"
            (select)="selectNode($event)"
            (delete)="removeNode($event)"
            (move)="onNodeMove($event)"
            (connectStart)="onConnectStart($event)"
          />
        }
      </div>
    </div>
  `,
  styles: [
    `
      :host {
        display: block;
      }
      .pl-surface {
        position: relative;
        overflow: auto;
        width: 100%;
        height: 62vh;
        min-height: 420px;
        border: 1px solid var(--border);
        border-radius: var(--radius);
        background: var(--surface-2);
        background-image: radial-gradient(var(--border) 1px, transparent 1px);
        background-size: 22px 22px;
      }
      .pl-surface:focus-visible {
        outline: 2px solid var(--accent);
        outline-offset: -2px;
      }
      .pl-content {
        position: relative;
      }
      .pl-wires {
        position: absolute;
        top: 0;
        left: 0;
        pointer-events: none;
      }
      .pl-wires :is(g, path) {
        pointer-events: auto;
      }
      .pl-temp {
        fill: none;
        stroke: var(--accent);
        stroke-width: 2;
        stroke-dasharray: 5 4;
        pointer-events: none;
      }
      .pl-temp.valid {
        stroke: var(--success);
      }
      .pl-temp.invalid {
        stroke: var(--danger);
      }
    `,
  ],
})
export class PipelineCanvasComponent {
  @ViewChild('content', { static: true }) private content!: ElementRef<HTMLElement>;

  readonly nodes = signal<CanvasNode[]>([]);
  readonly connections = signal<ConnectionJson[]>([]);
  readonly selectedId = signal<string | null>(null);

  /**
   * Uploaded watermark-image files, keyed by WATERMARK node id (client-only — the bytes never live
   * on the wire node, only the basename does via `wmImageName`). Sent on Run as `nodeAssets` parts.
   */
  private readonly nodeAssets = new Map<string, File>();

  /**
   * Uploaded source File objects, keyed by SOURCE node id (client-only — the wire node carries only
   * the basenames via `files`). Each SOURCE node owns its own upload set; the page gathers them all
   * (deduped by name) into the multipart `files` on Run. Stored refs are reused so the inspector's
   * embedded drop zone doesn't churn between change-detection passes.
   */
  private readonly sourceFiles = new Map<string, File[]>();
  private static readonly NO_FILES: File[] = [];

  readonly selectedNode = computed(() => this.nodes().find((n) => n.id === this.selectedId()) ?? null);

  /** Runnable when at least one SOURCE has files and at least one operation node exists. */
  readonly runnable = computed(() => {
    const ns = this.nodes();
    return ns.some((n) => n.kind === 'SOURCE' && n.files.length > 0) && ns.some((n) => n.kind !== 'SOURCE');
  });

  private seq = 0;

  // Pending connection gesture (signal so the source port can light up while dragging).
  readonly pendingFrom = signal<string | null>(null);
  readonly hoverTargetId = signal<string | null>(null);
  readonly hoverValid = signal(false);
  private readonly tempEnd = signal<Point | null>(null);

  readonly contentSize = computed(() => {
    let w = 1400;
    let h = 900;
    for (const n of this.nodes()) {
      w = Math.max(w, n.x + CARD_W + 260);
      h = Math.max(h, n.y + 240);
    }
    return { w, h };
  });

  /** Node ids whose INPUT port has a wire (target side) — drives the accent port fill. */
  readonly inConnectedIds = computed(() => new Set(this.connections().map((c) => c.toNodeId)));
  /** Node ids whose OUTPUT port has a wire (source side). */
  readonly outConnectedIds = computed(() => new Set(this.connections().map((c) => c.fromNodeId)));

  readonly wires = computed<Wire[]>(() => {
    const byId = new Map(this.nodes().map((n) => [n.id, n]));
    const out: Wire[] = [];
    for (const c of this.connections()) {
      const f = byId.get(c.fromNodeId);
      const t = byId.get(c.toNodeId);
      if (f && t) out.push({ conn: c, from: outPortCenter(f), to: inPortCenter(t) });
    }
    return out;
  });

  readonly tempWire = computed(() => {
    const end = this.tempEnd();
    const from = this.pendingFrom();
    if (!from || !end) return null;
    const f = this.nodes().find((n) => n.id === from);
    return f ? wirePath(outPortCenter(f), end) : null;
  });

  // --- public API used by the host page ---------------------------------

  mintId(): string {
    let id = `n${++this.seq}`;
    while (this.nodes().some((n) => n.id === id)) id = `n${++this.seq}`;
    return id;
  }

  addNode(kind: NodeKindName): CanvasNode {
    const existing = this.nodes().length;
    const x = 60 + (existing % 5) * 60;
    const y = 60 + (existing % 8) * 40 + (kind === 'SOURCE' ? 0 : 120);
    const node = newCanvasNode(this.mintId(), kind, x, y);
    this.nodes.set([...this.nodes(), node]);
    this.selectedId.set(node.id);
    return node;
  }

  patchSelected(partial: Partial<CanvasNode>): void {
    const id = this.selectedId();
    if (!id) return;
    this.nodes.set(this.nodes().map((n) => (n.id === id ? { ...n, ...partial } : n)));
  }

  /**
   * Set (or clear, when {@code file} is null) the selected WATERMARK node's uploaded image. Stores
   * the File keyed by node id and records its basename in {@code wmImageName} for the wire node.
   */
  setSelectedAsset(file: File | null): void {
    const id = this.selectedId();
    if (!id) return;
    if (file) {
      this.nodeAssets.set(id, file);
      this.patchSelected({ wmImageName: file.name });
    } else {
      this.nodeAssets.delete(id);
      this.patchSelected({ wmImageName: '' });
    }
  }

  /** Uploaded watermark-image files for nodes still present in the graph (sent as `nodeAssets`). */
  assetFiles(): File[] {
    const ids = new Set(this.nodes().map((n) => n.id));
    return [...this.nodeAssets.entries()].filter(([id]) => ids.has(id)).map(([, file]) => file);
  }

  /** File objects uploaded into the currently-selected SOURCE node (stable ref for the drop zone). */
  selectedSourceFiles(): File[] {
    const id = this.selectedId();
    if (!id) return PipelineCanvasComponent.NO_FILES;
    return this.sourceFiles.get(id) ?? PipelineCanvasComponent.NO_FILES;
  }

  /**
   * Replace the selected SOURCE node's uploaded files. Stores the File objects keyed by node id and
   * mirrors their basenames into the node's `files` (the wire representation the backend resolves).
   */
  setSelectedSourceFiles(files: File[]): void {
    const id = this.selectedId();
    if (!id) return;
    if (files.length) this.sourceFiles.set(id, files);
    else this.sourceFiles.delete(id);
    this.patchSelected({ files: files.map((f) => f.name) });
  }

  /** All uploaded source files across every SOURCE node still in the graph, deduped by name. */
  allSourceFiles(): File[] {
    const ids = new Set(this.nodes().map((n) => n.id));
    const byName = new Map<string, File>();
    for (const [id, files] of this.sourceFiles.entries()) {
      if (!ids.has(id)) continue;
      for (const f of files) if (!byName.has(f.name)) byName.set(f.name, f);
    }
    return [...byName.values()];
  }

  removeNode(id: string): void {
    this.nodes.set(this.nodes().filter((n) => n.id !== id));
    this.connections.set(this.connections().filter((c) => c.fromNodeId !== id && c.toNodeId !== id));
    this.nodeAssets.delete(id);
    this.sourceFiles.delete(id);
    if (this.selectedId() === id) this.selectedId.set(null);
  }

  removeSelected(): void {
    const id = this.selectedId();
    if (id) this.removeNode(id);
  }

  clear(): void {
    this.nodes.set([]);
    this.connections.set([]);
    this.nodeAssets.clear();
    this.sourceFiles.clear();
    this.selectedId.set(null);
    this.seq = 0;
  }

  hasSource(): boolean {
    return this.nodes().some((n) => n.kind === 'SOURCE');
  }

  loadModel(model: PipelineModelJson): void {
    this.clear();
    const nodes = (model.nodes ?? []).map(fromWireNode);
    const ids = new Set(nodes.map((n) => n.id));
    const conns = (model.connections ?? []).filter(
      (c) => ids.has(c.fromNodeId) && ids.has(c.toNodeId),
    );
    for (const n of nodes) this.bumpSeq(n.id);
    this.nodes.set(nodes);
    this.connections.set(conns);
    this.selectedId.set(null);
  }

  toModel(): PipelineModelJson {
    return {
      nodes: this.nodes().map(toWireNode),
      connections: [...this.connections()],
    };
  }

  private bumpSeq(id: string): void {
    const m = /^n(\d+)$/.exec(id);
    if (m) this.seq = Math.max(this.seq, parseInt(m[1], 10));
  }

  // --- selection / surface ----------------------------------------------

  selectNode(id: string): void {
    this.selectedId.set(id);
  }

  onSurfaceDown(e: PointerEvent): void {
    // A press on the bare surface (not a node) clears the selection.
    if (e.target === this.content.nativeElement || (e.target as HTMLElement).classList.contains('pl-surface')) {
      this.selectedId.set(null);
    }
  }

  @HostListener('keydown', ['$event'])
  onKeydown(e: KeyboardEvent): void {
    const tag = (e.target as HTMLElement)?.tagName;
    if (tag === 'INPUT' || tag === 'SELECT' || tag === 'TEXTAREA') return;
    if (e.key === 'Delete' || e.key === 'Backspace') {
      if (this.selectedId()) {
        e.preventDefault();
        this.removeSelected();
      }
    }
  }

  // --- node drag ---------------------------------------------------------

  onNodeMove(ev: { id: string; x: number; y: number }): void {
    this.nodes.set(this.nodes().map((n) => (n.id === ev.id ? { ...n, x: ev.x, y: ev.y } : n)));
  }

  // --- connection gesture ------------------------------------------------

  onConnectStart(ev: { id: string; clientX: number; clientY: number }): void {
    this.pendingFrom.set(ev.id);
    this.selectedId.set(ev.id);
    this.updateTemp(ev.clientX, ev.clientY);
    window.addEventListener('pointermove', this.onConnMove);
    window.addEventListener('pointerup', this.onConnUp);
  }

  private readonly onConnMove = (e: PointerEvent) => {
    e.preventDefault();
    this.updateTemp(e.clientX, e.clientY);
  };

  private readonly onConnUp = (e: PointerEvent) => {
    window.removeEventListener('pointermove', this.onConnMove);
    window.removeEventListener('pointerup', this.onConnUp);
    const target = this.nearestInput(e.clientX, e.clientY);
    const from = this.pendingFrom();
    if (from && target && this.canConnect(from, target)) {
      this.connections.set([
        ...this.connections(),
        { fromNodeId: from, toNodeId: target },
      ]);
    }
    this.pendingFrom.set(null);
    this.tempEnd.set(null);
    this.hoverTargetId.set(null);
    this.hoverValid.set(false);
  };

  private updateTemp(clientX: number, clientY: number): void {
    const p = this.toContent(clientX, clientY);
    this.tempEnd.set(p);
    const target = this.nearestInput(clientX, clientY);
    this.hoverTargetId.set(target);
    const from = this.pendingFrom();
    this.hoverValid.set(target != null && from != null && this.canConnect(from, target));
  }

  private toContent(clientX: number, clientY: number): Point {
    const r = this.content.nativeElement.getBoundingClientRect();
    return { x: clientX - r.left, y: clientY - r.top };
  }

  /** Id of the node whose INPUT port is nearest the cursor (within radius), or null. */
  private nearestInput(clientX: number, clientY: number): string | null {
    const p = this.toContent(clientX, clientY);
    let best: string | null = null;
    let bestD = Number.MAX_VALUE;
    for (const n of this.nodes()) {
      if (n.kind === 'SOURCE') continue;
      const c = inPortCenter(n);
      const d = Math.hypot(c.x - p.x, c.y - p.y);
      if (d <= PORT_HIT_RADIUS && d < bestD) {
        bestD = d;
        best = n.id;
      }
    }
    return best;
  }

  private canConnect(fromId: string, toId: string): boolean {
    if (fromId === toId) return false;
    const to = this.nodes().find((n) => n.id === toId);
    if (!to || to.kind === 'SOURCE') return false;
    if (this.connections().some((c) => c.fromNodeId === fromId && c.toNodeId === toId)) return false;
    return !this.reaches(toId, fromId); // adding from→to must not close a cycle
  }

  /** True if `target` is reachable from `start` following connection direction. */
  private reaches(start: string, target: string): boolean {
    const conns = this.connections();
    const stack = [start];
    const seen = new Set<string>();
    while (stack.length) {
      const cur = stack.pop()!;
      if (cur === target) return true;
      if (seen.has(cur)) continue;
      seen.add(cur);
      for (const c of conns) if (c.fromNodeId === cur) stack.push(c.toNodeId);
    }
    return false;
  }

  removeConnection(conn: ConnectionJson): void {
    this.connections.set(
      this.connections().filter(
        (c) => !(c.fromNodeId === conn.fromNodeId && c.toNodeId === conn.toNodeId),
      ),
    );
  }
}
