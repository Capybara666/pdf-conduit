import { Component, EventEmitter, Input, Output, signal } from '@angular/core';
import { TranslocoModule } from '@jsverse/transloco';

import { Point, wirePath } from './pipeline-geometry';

/**
 * One connection wire, rendered as an SVG group inside the canvas overlay.
 *
 * Draws a cubic-bezier path between an output and an input port centre, plus a
 * wide transparent hit path so the thin wire is easy to grab, and a delete
 * control that appears on hover at the wire's midpoint. Endpoints are inputs so
 * the wire auto-follows node moves (the canvas recomputes them from x/y).
 */
@Component({
  selector: '[app-pipeline-connection]',
  standalone: true,
  imports: [TranslocoModule],
  template: `
    <svg:path class="wire" [class.danger]="delHover()" [attr.d]="d()" />
    <svg:path class="wire-hit" [attr.d]="d()" />
    <svg:g class="wire-del" [attr.transform]="'translate(' + midX() + ',' + midY() + ')'">
      <svg:circle
        r="9"
        role="button"
        tabindex="0"
        [attr.aria-label]="'pipeline.canvas.removeConnection' | transloco"
        (pointerenter)="delHover.set(true)"
        (pointerleave)="delHover.set(false)"
        (focus)="delHover.set(true)"
        (blur)="delHover.set(false)"
        (pointerdown)="onDelete($event)"
        (keydown.enter)="onDelete($event)"
        (keydown.space)="onDelete($event)"
      />
      <svg:text x="0" y="1" text-anchor="middle" dominant-baseline="middle">✕</svg:text>
    </svg:g>
  `,
  styles: [
    `
      :host {
        cursor: default;
      }
      .wire {
        fill: none;
        /* Accent (blue) by default so wires read clearly across all 6 themes. */
        stroke: var(--accent);
        stroke-width: 2;
        pointer-events: none;
        transition: stroke 0.1s, stroke-width 0.1s;
      }
      /* Emphasise the whole wire on hover (colour unchanged — already accent). */
      :host(:hover) .wire {
        stroke-width: 3;
      }
      /* Hovering/focusing the delete control flags THIS wire for removal in red.
         The second selector out-specifies the :host(:hover) rule above. */
      .wire.danger,
      :host(:hover) .wire.danger {
        stroke: var(--danger);
        stroke-width: 3.5;
      }
      .wire-hit {
        fill: none;
        stroke: transparent;
        stroke-width: 16;
        cursor: pointer;
      }
      .wire-del {
        opacity: 0;
        pointer-events: none;
        transition: opacity 0.1s;
      }
      :host(:hover) .wire-del {
        opacity: 1;
        pointer-events: all;
      }
      .wire-del circle {
        fill: var(--danger);
        cursor: pointer;
      }
      .wire-del text {
        fill: #fff;
        font-size: 11px;
        pointer-events: none;
      }
    `,
  ],
})
export class PipelineConnectionComponent {
  @Input({ required: true }) from!: Point;
  @Input({ required: true }) to!: Point;
  @Output() remove = new EventEmitter<void>();

  /** True while the delete control is hovered/focused — turns this wire red. */
  readonly delHover = signal(false);

  d(): string {
    return wirePath(this.from, this.to);
  }
  midX(): number {
    return (this.from.x + this.to.x) / 2;
  }
  midY(): number {
    return (this.from.y + this.to.y) / 2;
  }

  onDelete(e: Event): void {
    e.stopPropagation();
    e.preventDefault();
    this.remove.emit();
  }
}
