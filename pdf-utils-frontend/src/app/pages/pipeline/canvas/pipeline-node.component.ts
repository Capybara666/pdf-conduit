import { Component, ElementRef, EventEmitter, Input, Output, inject } from '@angular/core';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';

import { CanvasNode } from '../../../core/pipeline.models';
import { CARD_W, PORT_TOP } from './pipeline-geometry';

/** NodeKind → operation id used for the existing `op.<id>.label` i18n lookup. */
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

const ICONS: Record<string, string> = {
  SOURCE: '📄',
  MERGE: '🔗',
  IMAGES_TO_PDF: '🖼️',
  EXTRACT: '✂️',
  COMPRESS: '🗜️',
  ROTATE: '🔄',
  ARRANGE: '🔀',
  PROTECT: '🔒',
  UNLOCK: '🔓',
  METADATA: 'ℹ️',
  WATERMARK: '💧',
  TO_IMAGES: '🎞️',
  TO_TEXT: '🔤',
};

/**
 * A draggable node card on the pipeline canvas.
 *
 * The header is the drag handle (emits live x/y). The OUTPUT port starts a
 * connection gesture (the canvas tracks the drag + hit-tests input ports on
 * release). Non-SOURCE nodes also render an INPUT port. Ports are purely visual
 * here — their canvas coordinates are derived from x/y by `pipeline-geometry`.
 */
@Component({
  selector: 'app-pipeline-node',
  standalone: true,
  imports: [TranslocoModule],
  template: `
    <div
      class="pl-node"
      [class.selected]="selected"
      [style.left.px]="node.x"
      [style.top.px]="node.y"
      [style.width.px]="cardW"
      (pointerdown)="onSelect($event)"
    >
      <div class="pl-node-head" (pointerdown)="onHeadDown($event)">
        <span class="pl-ico" aria-hidden="true">{{ icon() }}</span>
        <span class="pl-title">{{ title() }}</span>
        <button
          type="button"
          class="pl-close"
          [attr.aria-label]="'pipeline.canvas.removeNode' | transloco"
          (pointerdown)="$event.stopPropagation()"
          (click)="delete.emit(node.id)"
        >
          ✕
        </button>
      </div>
      <p class="pl-summary">{{ summary() }}</p>

      @if (node.kind !== 'SOURCE') {
        <span
          class="pl-port pl-port-in"
          [class.connected]="inConnected"
          [class.valid]="inValid"
          [class.invalid]="inInvalid"
          [style.top.px]="portTop"
          [attr.aria-label]="'pipeline.canvas.inPort' | transloco"
        ></span>
      }
      <span
        class="pl-port pl-port-out"
        [class.connected]="outConnected"
        [class.active]="outActive"
        [style.top.px]="portTop"
        [attr.aria-label]="'pipeline.canvas.outPort' | transloco"
        (pointerdown)="onPortDown($event)"
      ></span>
    </div>
  `,
  styles: [
    `
      :host {
        position: absolute;
        top: 0;
        left: 0;
      }
      .pl-node {
        position: absolute;
        background: var(--surface);
        border: 1px solid var(--border-strong);
        border-radius: 10px;
        box-shadow: var(--shadow);
        user-select: none;
        touch-action: none;
      }
      .pl-node.selected {
        border-color: var(--accent);
        box-shadow: 0 0 0 2px var(--accent-soft), var(--shadow);
      }
      .pl-node-head {
        display: flex;
        align-items: center;
        gap: 0.4rem;
        padding: 0.4rem 0.5rem;
        border-bottom: 1px solid var(--border);
        cursor: grab;
        border-radius: 10px 10px 0 0;
        background: var(--surface-2);
      }
      .pl-node-head:active {
        cursor: grabbing;
      }
      .pl-ico {
        font-size: 0.95rem;
      }
      .pl-title {
        flex: 1;
        min-width: 0;
        font-weight: 600;
        font-size: 0.85rem;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .pl-close {
        border: none;
        background: transparent;
        color: var(--text-muted);
        cursor: pointer;
        font-size: 0.8rem;
        padding: 0 0.2rem;
        border-radius: 4px;
        line-height: 1;
      }
      .pl-close:hover {
        color: var(--danger);
      }
      .pl-summary {
        margin: 0;
        padding: 0.45rem 0.6rem 0.55rem;
        font-size: 0.75rem;
        color: var(--text-muted);
        white-space: pre-line;
        word-break: break-word;
      }
      .pl-port {
        position: absolute;
        width: 14px;
        height: 14px;
        border-radius: 50%;
        background: var(--surface);
        border: 2px solid var(--border-strong);
        transform: translate(-50%, -50%);
        z-index: 2;
      }
      .pl-port-in {
        left: 0;
      }
      .pl-port-out {
        left: 100%;
        cursor: crosshair;
      }
      .pl-port-out:hover,
      .pl-port-out.active {
        border-color: var(--accent);
        background: var(--accent);
        box-shadow: 0 0 0 3px var(--accent-soft);
      }
      .pl-port.connected {
        border-color: var(--accent);
        background: var(--accent);
      }
      .pl-port.valid {
        border-color: var(--success);
        background: var(--success);
        box-shadow: 0 0 0 3px var(--success-soft);
      }
      .pl-port.invalid {
        border-color: var(--danger);
        background: var(--danger);
        box-shadow: 0 0 0 3px var(--danger-soft);
      }
    `,
  ],
})
export class PipelineNodeComponent {
  private readonly transloco = inject(TranslocoService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  @Input({ required: true }) node!: CanvasNode;
  @Input() selected = false;
  /** Live connection-hover state, driven by the canvas onto the target's input port. */
  @Input() inValid = false;
  @Input() inInvalid = false;
  /** Whether each port currently has a wire attached — accent when connected, plain otherwise. */
  @Input() inConnected = false;
  @Input() outConnected = false;
  /** True while a connection is being dragged out of this node's output port. */
  @Input() outActive = false;

  @Output() select = new EventEmitter<string>();
  @Output() delete = new EventEmitter<string>();
  @Output() move = new EventEmitter<{ id: string; x: number; y: number }>();
  @Output() connectStart = new EventEmitter<{ id: string; clientX: number; clientY: number }>();

  protected readonly cardW = CARD_W;
  protected readonly portTop = PORT_TOP;

  private dragOff: { dx: number; dy: number } | null = null;

  icon(): string {
    return ICONS[this.node.kind] ?? '▫️';
  }

  title(): string {
    if (this.node.kind === 'SOURCE') return this.transloco.translate('pipeline.canvas.source');
    const op = KIND_TO_OP[this.node.kind];
    return op ? this.transloco.translate(`op.${op}.label`) : this.node.kind;
  }

  summary(): string {
    const t = (k: string, p?: Record<string, unknown>) => this.transloco.translate(k, p);
    const n = this.node;
    switch (n.kind) {
      case 'SOURCE':
        return n.files.length
          ? n.files.slice(0, 4).join('\n') + (n.files.length > 4 ? `\n+${n.files.length - 4}` : '')
          : t('pipeline.canvas.summary.noFiles');
      case 'EXTRACT':
        return (
          t('pipeline.canvas.summary.pages', { pages: n.pages || t('pipeline.canvas.summary.all') }) +
          (n.splitMode === 'SEPARATE' ? ' · ' + t('pipeline.canvas.summary.separate') : '')
        );
      case 'ROTATE':
        return (
          t('pipeline.canvas.summary.pages', { pages: n.pages || t('pipeline.canvas.summary.all') }) +
          ` · ${n.angle}°`
        );
      case 'ARRANGE':
        return t('pipeline.canvas.summary.order', { order: n.order || t('pipeline.canvas.summary.natural') });
      case 'COMPRESS':
        return t('pipeline.canvas.summary.target', { size: n.targetSize });
      case 'IMAGES_TO_PDF':
        return t('pipeline.canvas.summary.pageSize', { size: n.pageSize });
      case 'MERGE':
        return t('pipeline.canvas.summary.merge');
      case 'PROTECT':
      case 'UNLOCK':
        return t(n.password ? 'pipeline.canvas.summary.hasPassword' : 'pipeline.canvas.summary.noPassword');
      case 'METADATA':
        return t(n.metaStrip ? 'pipeline.canvas.summary.metaStrip' : 'pipeline.canvas.summary.metaEdit');
      case 'WATERMARK':
        return n.wmText || '—';
      case 'TO_IMAGES':
        return `${n.imageFormat} · ${n.imageDpi} DPI`;
      case 'TO_TEXT':
        return `.${n.textFormat.toLowerCase()}`;
      default:
        return '';
    }
  }

  onSelect(_e: PointerEvent): void {
    this.select.emit(this.node.id);
  }

  // --- header drag -------------------------------------------------------

  onHeadDown(e: PointerEvent): void {
    if (e.button !== 0) return;
    this.select.emit(this.node.id);
    const el = this.host.nativeElement.querySelector('.pl-node-head') as HTMLElement;
    el.setPointerCapture(e.pointerId);
    this.dragOff = { dx: e.clientX - this.node.x, dy: e.clientY - this.node.y };
    el.addEventListener('pointermove', this.onHeadMove);
    el.addEventListener('pointerup', this.onHeadUp);
    e.stopPropagation();
    e.preventDefault();
  }

  private readonly onHeadMove = (e: PointerEvent) => {
    if (!this.dragOff) return;
    const x = Math.max(0, e.clientX - this.dragOff.dx);
    const y = Math.max(0, e.clientY - this.dragOff.dy);
    this.move.emit({ id: this.node.id, x, y });
  };

  private readonly onHeadUp = (e: PointerEvent) => {
    const el = this.host.nativeElement.querySelector('.pl-node-head') as HTMLElement;
    el.releasePointerCapture?.(e.pointerId);
    el.removeEventListener('pointermove', this.onHeadMove);
    el.removeEventListener('pointerup', this.onHeadUp);
    this.dragOff = null;
  };

  // --- connection gesture (delegated to the canvas) ----------------------

  onPortDown(e: PointerEvent): void {
    if (e.button !== 0) return;
    e.stopPropagation();
    e.preventDefault();
    this.connectStart.emit({ id: this.node.id, clientX: e.clientX, clientY: e.clientY });
  }
}
