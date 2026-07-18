import { Component, EventEmitter, Input, Output } from '@angular/core';
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
    <svg:path class="wire" [attr.d]="d()" />
    <svg:path class="wire-hit" [attr.d]="d()" />
    <svg:g class="wire-del" [attr.transform]="'translate(' + midX() + ',' + midY() + ')'">
      <svg:circle
        r="9"
        role="button"
        tabindex="0"
        [attr.aria-label]="'pipeline.canvas.removeConnection' | transloco"
        (pointerdown)="onDelete($event)"
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
        stroke: var(--border-strong);
        stroke-width: 2;
        pointer-events: none;
      }
      :host(:hover) .wire {
        stroke: var(--accent);
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

  d(): string {
    return wirePath(this.from, this.to);
  }
  midX(): number {
    return (this.from.x + this.to.x) / 2;
  }
  midY(): number {
    return (this.from.y + this.to.y) / 2;
  }

  onDelete(e: PointerEvent): void {
    e.stopPropagation();
    e.preventDefault();
    this.remove.emit();
  }
}
