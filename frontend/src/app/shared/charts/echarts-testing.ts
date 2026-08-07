import { provideEchartsCore } from 'ngx-echarts';
import type { Provider } from '@angular/core';

/**
 * echarts, stubbed, for specs that mount a component containing a chart.
 *
 * Two things make the real library unusable under `ng test`, and neither is worth working around:
 *
 * - jsdom has no canvas, so `echarts.init` cannot get a 2D context;
 * - jsdom has no `ResizeObserver`, and `NgxEchartsDirective.ngOnInit` throws outright without one.
 *
 * Neither is a loss, because a canvas is not assertable anyway. What a spec should check is the
 * **option object** (see `chart-options.spec.ts`) and the visually-hidden data table each chart
 * renders beside itself — both of which this stub leaves completely intact.
 *
 * The stub records the options it was given, so a spec that genuinely needs to know what the
 * chart was told can read `lastOptionsFor()` instead of reaching into the component.
 */
const recorded: Record<string, unknown>[] = [];

// Assigned arrow functions rather than empty method bodies: the point of each of these is that it
// does nothing, and `no-empty-function` is right to ask that saying so be deliberate.
const noop = (): void => undefined;

class ChartStub {
  private disposed = false;

  setOption(options: Record<string, unknown>): void {
    recorded.push(options);
  }

  readonly on = noop;
  readonly off = noop;
  readonly resize = noop;
  readonly showLoading = noop;
  readonly hideLoading = noop;

  isDisposed(): boolean {
    return this.disposed;
  }

  dispose(): void {
    this.disposed = true;
  }
}

class ResizeObserverStub {
  readonly observe = noop;
  readonly unobserve = noop;
  readonly disconnect = noop;
}

/** Every option object handed to a chart since the last [resetEchartsStub]. */
export function recordedChartOptions(): readonly Record<string, unknown>[] {
  return recorded;
}

export function resetEchartsStub(): void {
  recorded.length = 0;
}

/**
 * Add to a spec's `providers` to make any component containing a chart mountable.
 *
 * Installs the `ResizeObserver` stub as a side effect: it has to exist on `globalThis` before the
 * directive's `ngOnInit` runs, and a provider is the one hook every such spec already has.
 */
export function provideEchartsTesting(): Provider {
  globalThis.ResizeObserver ??= ResizeObserverStub as unknown as typeof ResizeObserver;
  return provideEchartsCore({ echarts: { init: () => new ChartStub() } });
}
