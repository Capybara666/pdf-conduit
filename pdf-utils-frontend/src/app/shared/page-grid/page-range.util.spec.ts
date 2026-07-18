import { toCompactRange, toOrderString } from './page-range.util';

describe('page-range.util', () => {
  describe('toCompactRange', () => {
    it('collapses consecutive runs and keeps singletons', () => {
      expect(toCompactRange([1, 3, 5, 6, 7, 8], 10)).toBe('1,3,5-8');
    });

    it('sorts and de-duplicates input', () => {
      expect(toCompactRange([8, 5, 6, 3, 1, 7, 5], 10)).toBe('1,3,5-8');
    });

    it('returns "" when all pages are selected', () => {
      expect(toCompactRange([1, 2, 3, 4], 4)).toBe('');
    });

    it('returns "" for an empty selection', () => {
      expect(toCompactRange([], 4)).toBe('');
    });

    it('handles a single page', () => {
      expect(toCompactRange([3], 10)).toBe('3');
    });

    it('does not treat a full-length-but-shifted set as all', () => {
      expect(toCompactRange([2, 3, 4, 5], 5)).toBe('2-5');
    });
  });

  describe('toOrderString', () => {
    it('joins the visual order with commas, keeping repeats', () => {
      expect(toOrderString([3, 1, 2])).toBe('3,1,2');
      expect(toOrderString([1, 1, 2])).toBe('1,1,2');
    });
  });
});
