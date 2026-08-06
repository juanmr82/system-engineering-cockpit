/**
 * Test-only: wait for ag-grid to actually draw its rows.
 *
 * ag-grid renders rows on an animation frame rather than synchronously, so after a `rowData`
 * change the new rows are **not** in the DOM when Angular's `whenStable()` resolves — and jsdom
 * schedules `requestAnimationFrame` on a ~16ms timer, so awaiting a microtask is not enough
 * either. Without this wait a spec silently asserts against the *previous* render, which is worse
 * than failing: a test that reads stale rows passes for the wrong reason as often as it fails.
 *
 * Two frames' worth. Not imported by any application code, so it never reaches a bundle.
 */
export function flushGridFrames(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 40));
}
