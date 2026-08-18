import { DestroyRef, Injectable, inject } from '@angular/core';
import type { ELK } from 'elkjs/lib/elk-api';
import { buildElkGraph, readElkResult } from './elk-graph';
import type { LayoutRequest, LayoutResult } from './elk-graph';

/**
 * Runs ELK, in a Web Worker when there is one (docs/REQ_BREAKDOWN_GRAPH_VIEW.md §4.2).
 *
 * **ELK owns the worker; we do not.** The spec says to run ELK in a worker, and the shape that
 * looks right — our own worker file importing `elk.bundled.js` — cannot work: see the comment in
 * `elk.worker.ts`. `elk-api` takes a `workerFactory` for exactly this, so the split is that the
 * pure functions run here, on the main thread, where they cost microseconds, and the layered
 * layout runs in the worker, where it belongs.
 *
 * One ELK instance for the lifetime of the injector, not one per layout: constructing it means
 * spawning a worker and parsing ELK again, which is by far the most expensive thing this feature
 * does, and the depth control can ask for a fresh layout every 250 ms.
 *
 * **The in-thread fallback is not a nicety.** jsdom has no `Worker`, so without it every spec that
 * mounts the canvas would have to mock the layout instead of exercising it — and a fallback that
 * runs the same two pure functions is a much smaller thing to be wrong about than a mock.
 */
@Injectable()
export class GraphLayoutService {
  private engine: Promise<ELK> | null = null;
  private worker: Worker | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.terminate());
  }

  async layout(request: LayoutRequest): Promise<LayoutResult> {
    if (request.nodes.length === 0) {
      return { nodes: [], edges: [] };
    }

    const elk = await this.ensureEngine();
    const laid = await elk.layout(buildElkGraph(request));
    return readElkResult(laid, request);
  }

  private ensureEngine(): Promise<ELK> {
    if (!this.engine) {
      this.engine = this.createEngine();
    }
    return this.engine;
  }

  private async createEngine(): Promise<ELK> {
    if (typeof Worker === 'undefined') {
      // jsdom, and anything else without workers. `elk.bundled.js` runs ELK in-thread, which is
      // correct here: on a main thread `elk-worker.min.js` takes its export branch and the
      // in-thread path it needs actually exists.
      const { default: BundledELK } = await import('elkjs/lib/elk.bundled.js');
      return new BundledELK();
    }

    const { default: ELKApi } = await import('elkjs/lib/elk-api');
    return new ELKApi({
      workerFactory: () => {
        // This exact shape — `new URL(..., import.meta.url)` — is what the application builder
        // detects and compiles as a worker bundle. Anything computed, or a bare string, silently
        // ships nothing. `type: 'classic'` because elk-worker.min.js is UMD, not an ES module.
        this.worker = new Worker(new URL('./elk.worker', import.meta.url));
        return this.worker;
      },
    });
  }

  private terminate(): void {
    this.worker?.terminate();
    this.worker = null;
    this.engine = null;
  }
}
