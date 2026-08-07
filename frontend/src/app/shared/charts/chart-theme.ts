// The one seam where the design tokens cross into TypeScript (ADR 0008, mitigation 1).
//
// echarts draws to a canvas, so it cannot read a CSS custom property the way `_grid.scss` lets
// ag-grid read one. Rather than writing hex values into option objects — which would put a second,
// drifting copy of the palette in the codebase — the values are read *from the live stylesheet*
// once. `_tokens.scss` stays the single source of truth; this file only carries the names.
//
// **No hex literal belongs in this file or in any other .ts file.** A component that calls
// getComputedStyle itself, or writes '#00205b' into an option, defeats the whole arrangement.

/** Every token a chart is allowed to use. Extend here, and in `_tokens.scss` first. */
const CHART_TOKENS = [
  'sec-blue',
  'sec-blue-mid',
  'sec-blue-light',
  'sec-blue-pale',
  'sec-grey-blue',
  'sec-highlight-verified',
  'sec-highlight-tbd',
  'sec-highlight-undefined',
  'sec-highlight-error',
  'sec-highlight-meta',
  'sec-paper',
  'sec-wash',
  'sec-line',
  'sec-line-soft',
  'sec-ink',
  'sec-ink-2',
  'sec-ink-3',
  'sec-font',
] as const;

export type ChartToken = (typeof CHART_TOKENS)[number];

export type ChartTokens = Record<ChartToken, string>;

/**
 * Fallbacks used only when there is no live stylesheet to read — jsdom under `ng test`, and the
 * first paint of a chart created before styles.scss has applied.
 *
 * They are deliberately **not** the real palette: an empty string would produce an invisible chart
 * that looks like a rendering bug, while a neutral grey looks like what it is — unthemed. If a
 * chart ever renders grey in the browser, the tokens did not resolve, and that is the bug to fix
 * rather than a value to paper over here.
 */
const UNTHEMED = '#9aa7b1';

let cached: ChartTokens | null = null;

/**
 * The resolved token values, read once per document.
 *
 * Cached because `getComputedStyle` forces a style recalculation and a view can hold half a dozen
 * charts that would otherwise each trigger one on every option rebuild.
 */
export function chartTokens(): ChartTokens {
  if (cached) {
    return cached;
  }

  const styles =
    typeof getComputedStyle === 'function' && typeof document !== 'undefined'
      ? getComputedStyle(document.documentElement)
      : null;

  const resolved = {} as ChartTokens;
  for (const token of CHART_TOKENS) {
    resolved[token] = styles?.getPropertyValue(`--${token}`).trim() || UNTHEMED;
  }

  cached = resolved;
  return resolved;
}

/** Test-only: forget the memoised values so a spec can install its own stylesheet. */
export function resetChartTokens(): void {
  cached = null;
}
