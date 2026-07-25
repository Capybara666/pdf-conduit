/**
 * Counts the members of a ZIP the backend just returned.
 *
 * A partial batch answers with an archive of what survived plus an
 * `X-Batch-Failures` header naming what did not. The header alone tells us how
 * many files failed but not how many were asked for, and the SPA must not guess
 * the total — so the successes are read from the archive itself: the End Of
 * Central Directory record carries the entry count, and reading it needs only
 * the last few bytes of the blob (no unzipping, no dependency).
 *
 * Returns `null` whenever the count cannot be established honestly (not a ZIP,
 * truncated, or a ZIP64 archive whose real count lives in a different record).
 * Callers then fall back to reporting the failures only.
 */

/** `PK\005\006` — End Of Central Directory signature. */
const EOCD_SIGNATURE = 0x06054b50;

/** An EOCD record is 22 bytes plus its optional trailing comment. */
const EOCD_MIN_BYTES = 22;

/** The comment length field is 16-bit, so the record starts at most this far from the end. */
const MAX_COMMENT_BYTES = 0xffff;

/** Byte offset of the 16-bit "total entries in the central directory" field. */
const TOTAL_ENTRIES_OFFSET = 10;

/** A 16-bit field of all ones is the ZIP64 sentinel — the true value is elsewhere. */
const ZIP64_SENTINEL = 0xffff;

export async function countZipEntries(blob: Blob): Promise<number | null> {
  if (blob.size < EOCD_MIN_BYTES) return null;
  const tailLength = Math.min(blob.size, EOCD_MIN_BYTES + MAX_COMMENT_BYTES);

  let view: DataView;
  try {
    view = new DataView(await blob.slice(blob.size - tailLength).arrayBuffer());
  } catch {
    return null;
  }

  // Scan backwards: the last signature is the real EOCD (an entry's own bytes
  // could coincidentally repeat the pattern earlier in the tail).
  for (let i = view.byteLength - EOCD_MIN_BYTES; i >= 0; i--) {
    if (view.getUint32(i, true) !== EOCD_SIGNATURE) continue;
    const total = view.getUint16(i + TOTAL_ENTRIES_OFFSET, true);
    return total === ZIP64_SENTINEL ? null : total;
  }
  return null;
}
