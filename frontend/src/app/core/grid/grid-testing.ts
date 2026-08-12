import type { ComponentFixture } from '@angular/core/testing';

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

/**
 * Test-only: run change detection and frames until the grid's rendered text stops changing.
 *
 * [flushGridFrames] waits a fixed two frames, which is enough for a grid whose cells are plain
 * values and not enough for one whose rows carry Angular cell renderers — those mount on a later
 * frame, so a spec that asserts after exactly two frames sees *some* of the rows *some* of the
 * time. That is the worst kind of failure: it passes alone and fails in the suite.
 *
 * This waits for a result instead of a duration — two consecutive identical readings — and gives
 * up after a budget rather than hanging, so a grid that genuinely never draws still fails as the
 * assertion that follows rather than as a timeout with nothing named.
 *
 * **Do not call this while a request is in flight.** `whenStable()` does not resolve while an
 * `httpResource` is loading; it times the spec out rather than failing it.
 */
export async function settleGrid(fixture: ComponentFixture<unknown>, attempts = 15): Promise<void> {
  let previous: string | null = null;

  for (let attempt = 0; attempt < attempts; attempt += 1) {
    fixture.detectChanges();
    await fixture.whenStable();
    await flushGridFrames();
    fixture.detectChanges();

    const current = (fixture.nativeElement as HTMLElement).textContent ?? '';
    if (current === previous) return;
    previous = current;
  }
}
