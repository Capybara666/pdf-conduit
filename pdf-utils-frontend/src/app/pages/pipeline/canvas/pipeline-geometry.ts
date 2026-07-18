/**
 * Shared geometry for the pipeline canvas. The node card's visual port
 * positions (CSS, in `pipeline-node`) and the wire endpoints / hit-testing
 * (computed from a node's x/y, in `pipeline-canvas`) must agree, so both derive
 * from these constants — the single source of truth for the layout.
 */

/** Fixed node-card width in px (ports sit on its left/right edges). */
export const CARD_W = 208;

/** Distance in px from a node's top to the vertical centre of its ports. */
export const PORT_TOP = 30;

/** Max cursor→input-port distance (px) that still completes a connection. */
export const PORT_HIT_RADIUS = 26;

export interface Point {
  x: number;
  y: number;
}

/** Centre of a node's OUTPUT port, in canvas-content coordinates. */
export function outPortCenter(node: { x: number; y: number }): Point {
  return { x: node.x + CARD_W, y: node.y + PORT_TOP };
}

/** Centre of a node's INPUT port, in canvas-content coordinates. */
export function inPortCenter(node: { x: number; y: number }): Point {
  return { x: node.x, y: node.y + PORT_TOP };
}

/** Cubic-bezier `d` from an output point to an input point (horizontal ctrl offset). */
export function wirePath(from: Point, to: Point): string {
  const dx = Math.max(40, Math.abs(to.x - from.x) / 2);
  return `M ${from.x} ${from.y} C ${from.x + dx} ${from.y}, ${to.x - dx} ${to.y}, ${to.x} ${to.y}`;
}
