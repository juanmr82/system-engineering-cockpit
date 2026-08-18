import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { DoorsSettings } from './doors-settings';

/**
 * A stand-in for the browser's EventSource, which jsdom does not implement — see
 * `windchill-settings.spec.ts` for why this is required rather than a convenience.
 *
 * `instances` records every one created, in order — the multi-file queue opens a fresh stream per
 * file it watches, so a spec proving sequencing needs to reach the *current* one, not "the" one.
 */
class FakeEventSource {
  static instances: FakeEventSource[] = [];

  readonly listeners = new Map<string, (event: MessageEvent<string>) => void>();
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  closed = false;

  constructor(readonly url: string) {
    FakeEventSource.instances.push(this);
  }

  addEventListener(name: string, handler: (event: MessageEvent<string>) => void): void {
    this.listeners.set(name, handler);
  }

  close(): void {
    this.closed = true;
  }
}

/**
 * The DOORS settings page.
 *
 * What is worth testing: choosing files writes nothing, a queued file can be removed before it
 * runs, pressing *Import all* processes the queue **strictly one file at a time** — a second
 * request must not appear until the first file's run has reached a terminal status — and each of
 * the three things a file's outcome can be (started and finished, already imported, or refused) is
 * rendered against that file's own row. `HttpTestingController` cannot simulate real upload-progress
 * events (no actual byte transfer happens in a test), so percent rendering during transit is not
 * asserted; what is asserted is the request sequence and each row's final state.
 */
describe('DoorsSettings', () => {
  let fixture: ComponentFixture<DoorsSettings>;
  let httpTesting: HttpTestingController;

  const renderedText = (): string => fixture.nativeElement.textContent;

  const importButton = (): HTMLButtonElement | null =>
    Array.from(fixture.nativeElement.querySelectorAll('button')).find((button) =>
      (button as HTMLElement).textContent?.includes('Import all'),
    ) as HTMLButtonElement | null;

  const fileInput = (): HTMLInputElement =>
    fixture.nativeElement.querySelector('input[type="file"]');

  const removeButtonFor = (name: string): HTMLButtonElement | null => {
    const row = Array.from(fixture.nativeElement.querySelectorAll('li')).find((li) =>
      (li as HTMLElement).textContent?.includes(name),
    ) as HTMLElement | undefined;
    return row?.querySelector('button[aria-label="Remove"]') ?? null;
  };

  /** Answers the requests the page makes on load, so a spec starts from a settled page. */
  const settleLoad = async (): Promise<void> => {
    httpTesting
      .match((request) => request.url.startsWith('/api/v1/import/runs'))
      .forEach((request) => request.flush([]));
    httpTesting
      .match((request) => request.url === '/api/v1/import/importers')
      .forEach((request) => request.flush([]));
    await fixture.whenStable();
    fixture.detectChanges();
  };

  /** Selects one or more files in a single picker interaction, the way a real multi-select does. */
  const choose = async (...entries: readonly [name: string, contents: string][]): Promise<void> => {
    const input = fileInput();
    const files = entries.map(([name, contents]) => new File([contents], name, { type: 'application/json' }));
    Object.defineProperty(input, 'files', { value: files, configurable: true });
    input.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    fixture.detectChanges();
  };

  /** Flushes the GET a `watch()` call issues, answering with **that request's own run id** — the
   *  component only advances a row once a response's `runId` matches the one it started, so a
   *  second file's watch must not be answered with the first file's id. */
  const settleWatch = async (): Promise<void> => {
    await fixture.whenStable();
    httpTesting
      .match((pending) => pending.url.startsWith('/api/v1/import/runs/'))
      .forEach((pending) => {
        const runId = pending.request.url.split('/').pop();
        pending.flush({ runId, status: 'RUNNING', counters: {} });
      });
    await fixture.whenStable();
    fixture.detectChanges();
  };

  /** Delivers a terminal `status` SSE event on the most recently opened stream, carrying **that
   *  stream's own run id** (parsed off its URL) — what makes `ImportRunStore`'s run signal finish,
   *  which is what the component's own effect waits on to advance the queue to the next file. */
  const finishRun = async (status: 'SUCCEEDED' | 'FAILED', error: string | null = null): Promise<void> => {
    const source = FakeEventSource.instances.at(-1);
    const runId = source?.url.match(/\/runs\/([^/]+)\/events/)?.[1];
    const handler = source?.listeners.get('status');
    handler?.({
      data: JSON.stringify({ runId, status, finishedAt: '2026-01-01T00:00:00Z', warnings: 0, error }),
    } as MessageEvent<string>);
    await fixture.whenStable();
    fixture.detectChanges();
  };

  beforeEach(async () => {
    FakeEventSource.instances = [];
    (globalThis as { EventSource?: unknown }).EventSource = FakeEventSource;

    await TestBed.configureTestingModule({
      imports: [DoorsSettings],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideNoopAnimations(),
        provideRouter([]),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(DoorsSettings);
    httpTesting = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  afterEach(() => {
    httpTesting.verify();
  });

  /** Choosing is not importing (R7): nothing has been sent until the button is pressed. */
  it('sends nothing when files are merely chosen, and lists each as queued', async () => {
    await settleLoad();

    await choose(['module-a.json', '{}'], ['module-b.json', '{}']);

    expect(renderedText()).toContain('module-a.json');
    expect(renderedText()).toContain('module-b.json');
    expect(renderedText()).toContain('Queued');
    httpTesting.expectNone('/api/v1/doors/import');
  });

  it('cannot import until at least one file has been chosen', async () => {
    await settleLoad();

    expect(importButton()?.disabled).toBe(true);

    await choose(['module.json', '{}']);

    expect(importButton()?.disabled).toBe(false);
  });

  it('removes a queued file without ever requesting it', async () => {
    await settleLoad();
    await choose(['module.json', '{}']);

    removeButtonFor('module.json')?.click();
    fixture.detectChanges();

    expect(renderedText()).not.toContain('module.json');
    importButton()?.click();
    httpTesting.expectNone('/api/v1/doors/import');
  });

  it('imports a two-file queue strictly one at a time', async () => {
    await settleLoad();
    await choose(['module-a.json', '{"a":1}'], ['module-b.json', '{"b":1}']);

    importButton()?.click();
    const first = httpTesting.expectOne('/api/v1/doors/import');
    expect(first.request.body).toBe('{"a":1}');

    // The second file must not be requested while the first is still running.
    httpTesting.expectNone((request) => request.url === '/api/v1/doors/import' && request.body === '{"b":1}');

    first.flush({
      status: 'started',
      runId: 'run-1',
      moduleRef: 'ref-a',
      moduleName: 'Module A',
      objects: 10,
      checksum: 'aaa',
      warnings: [],
    });
    await settleWatch();
    // Still 'importing' at this point — the objects-read sentence is a succeeded-row message, not
    // shown while a row's own status line is still reporting phase/percent.
    expect(renderedText()).toContain('Importing module-a.json');

    httpTesting.expectNone((request) => request.url === '/api/v1/doors/import' && request.body === '{"b":1}');

    await finishRun('SUCCEEDED');
    // A finished run refreshes the last-run card the same way a 'skipped' result does.
    await settleLoad();

    expect(renderedText()).toContain('10 objects read from the file');

    const second = httpTesting.expectOne('/api/v1/doors/import');
    expect(second.request.body).toBe('{"b":1}');
    second.flush({
      status: 'started',
      runId: 'run-2',
      moduleRef: 'ref-b',
      moduleName: 'Module B',
      objects: 20,
      checksum: 'bbb',
      warnings: [],
    });
    await settleWatch();
    await finishRun('SUCCEEDED');
    await settleLoad();

    expect(renderedText()).toContain('20 objects read from the file');
  });

  /** The checksum gate means a re-upload of the same file starts no run — the row has to say so
   *  rather than reading as if nothing happened, and the queue still moves straight on (ADR 0019 §3). */
  it('advances immediately past a file that is unchanged since its last import', async () => {
    await settleLoad();
    await choose(['module-a.json', '{"a":1}'], ['module-b.json', '{"b":1}']);

    importButton()?.click();
    httpTesting.expectOne('/api/v1/doors/import').flush({
      status: 'skipped',
      moduleRef: 'ref-a',
      moduleName: 'Module A',
      objects: 10,
      checksum: 'aaa',
      warnings: [],
    });
    await fixture.whenStable();
    fixture.detectChanges();
    // A 'skipped' result starts no run, so this just refreshes the last-run card.
    await settleLoad();

    expect(renderedText()).toContain('unchanged since its last import');

    const second = httpTesting.expectOne('/api/v1/doors/import');
    expect(second.request.body).toBe('{"b":1}');
    second.flush({
      status: 'skipped',
      moduleRef: 'ref-b',
      moduleName: 'Module B',
      objects: 20,
      checksum: 'bbb',
      warnings: [],
    });
    await settleLoad();
  });

  it('shows the server sentence when a file is refused, and still moves on to the next one', async () => {
    await settleLoad();
    await choose(['broken.json', 'not json'], ['module-b.json', '{"b":1}']);

    importButton()?.click();
    httpTesting.expectOne('/api/v1/doors/import').flush(
      { title: 'That file is not a DOORS export', detail: 'The file is not valid JSON.' },
      { status: 400, statusText: 'Bad Request' },
    );
    await fixture.whenStable();
    fixture.detectChanges();

    expect(renderedText()).toContain('The file is not valid JSON.');

    const second = httpTesting.expectOne('/api/v1/doors/import');
    expect(second.request.body).toBe('{"b":1}');
    second.flush({
      status: 'skipped',
      moduleRef: 'ref-b',
      moduleName: 'Module B',
      objects: 20,
      checksum: 'bbb',
      warnings: [],
    });
    await settleLoad();
  });

  /** The R8 visibility gate on a re-import (ADR 0019 §4) — a 404 the server explains in a full
   *  sentence, shown the same way any other refusal is, scoped to that file's own row. */
  it('shows the server sentence when the module is not visible to this account', async () => {
    await settleLoad();
    await choose(['module.json', '{}']);

    importButton()?.click();
    httpTesting.expectOne('/api/v1/doors/import').flush(
      {
        title: 'This module cannot be imported',
        detail: 'This module has already been imported and is not currently visible to your account.',
      },
      { status: 404, statusText: 'Not Found' },
    );
    await fixture.whenStable();
    fixture.detectChanges();

    expect(renderedText()).toContain('not currently visible to your account');
  });
});
