/**
 * Pure helpers that turn page-grid models into the compact strings the backend
 * parsers (`PageRangeParser` / `PageOrderParser`) understand. Kept free of any
 * Angular / DOM dependency so they can be unit-tested in isolation.
 */

/**
 * Collapse a set of 1-based page numbers into a compact range string, e.g.
 * `[1,3,5,6,7,8]` → `"1,3,5-8"`. Input is sorted + de-duplicated first.
 * Returns `''` when every page (1..total) is selected — the backend treats a
 * blank range as "all pages", so an empty string keeps the wire payload minimal.
 */
export function toCompactRange(selected: number[], total: number): string {
  const unique = Array.from(new Set(selected.filter((n) => Number.isInteger(n) && n >= 1))).sort(
    (a, b) => a - b,
  );
  if (unique.length === 0) return '';
  if (total > 0 && unique.length === total && unique[unique.length - 1] === total && unique[0] === 1) {
    return '';
  }

  const parts: string[] = [];
  let runStart = unique[0];
  let prev = unique[0];
  for (let i = 1; i <= unique.length; i++) {
    const cur = unique[i];
    if (i < unique.length && cur === prev + 1) {
      prev = cur;
      continue;
    }
    parts.push(runStart === prev ? `${runStart}` : `${runStart}-${prev}`);
    if (i < unique.length) {
      runStart = cur;
      prev = cur;
    }
  }
  return parts.join(',');
}

/**
 * Join a visual page order (1-based, may contain repeats) into the comma
 * expression the arrange endpoint expects, e.g. `[3,1,2]` → `"3,1,2"`.
 */
export function toOrderString(order: number[]): string {
  return order.filter((n) => Number.isInteger(n) && n >= 1).join(',');
}
