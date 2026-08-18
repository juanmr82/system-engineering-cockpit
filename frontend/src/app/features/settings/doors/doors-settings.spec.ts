import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { DoorsSettings } from './doors-settings';

/**
 * A stand-in for the browser's EventSource, which jsdom does not implement — see
 * `windchill-settings.spec.ts` for why this is required rather than a convenience.
 */
class FakeEventSource {
  readonly listeners = new Map<string, (event: MessageEvent<string>) => void>();
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;

  constructor(readonly url: string) {}

  addEventListener(name: string, handler: (event: MessageEvent<string>) => void): void {
    this.listeners.set(name, handler);
  }

  closed = false;

  close(): void {
    this.closed = true;
  }
}

/**
 * The DOORS settings page.
 *
 * What is worth testing: choosing a file writes nothing, pressing Import sends the file's text
 * once, and the three things the answer can say — a run started, this file was already imported
 * (nothing to do), or the file was refused — are each rendered distinctly. `HttpTestingController`
 * cannot simulate real upload-progress events (no actual byte transfer happens in a test), so the
 * progress bar's rendering is not asserted here; what is asserted is the request and its outcome.
 */
describe('DoorsSettings', () => {
  let fixture: ComponentFixture<DoorsSettings>;
  let httpTesting: HttpTestingController;

  const renderedText = (): string => fixture.nativeElement.textContent;

  const importButton = (): HTMLButtonElement | null =>
    Array.from(fixture.nativeElement.querySelectorAll('button')).find((button) =>
      (button as HTMLElement).textContent?.includes('Import module'),
    ) as HTMLButtonElement | null;

  const fileInput = (): HTMLInputElement =>
    fixture.nativeElement.querySelector('input[type="file"]');

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

  const choose = async (name: string, contents: string): Promise<void> => {
    const input = fileInput();
    const file = new File([contents], name, { type: 'application/json' });
    Object.defineProperty(input, 'files', { value: [file], configurable: true });
    input.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    fixture.detectChanges();
  };

  const settleWatch = async (): Promise<void> => {
    await fixture.whenStable();
    httpTesting
      .match((pending) => pending.url.startsWith('/api/v1/import/runs/'))
      .forEach((pending) => pending.flush({ runId: 'run-1', status: 'RUNNING', counters: {} }));
    await fixture.whenStable();
    fixture.detectChanges();
  };

  beforeEach(async () => {
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
  it('sends nothing when a file is merely chosen', async () => {
    await settleLoad();

    await choose('module.json', '{}');

    expect(renderedText()).toContain('module.json');
    httpTesting.expectNone('/api/v1/doors/import');
  });

  it('cannot import until a file has been chosen', async () => {
    await settleLoad();

    expect(importButton()?.disabled).toBe(true);

    await choose('module.json', '{}');

    expect(importButton()?.disabled).toBe(false);
  });

  it('posts the file text once and reports the run that started', async () => {
    await settleLoad();
    await choose('module.json', '{}');

    importButton()?.click();
    const request = httpTesting.expectOne('/api/v1/doors/import');
    expect(request.request.body).toBe('{}');

    request.flush({
      status: 'started',
      runId: 'run-1',
      moduleRef: 'ref-1',
      moduleName: 'SRD',
      objects: 42,
      checksum: 'abc123',
      warnings: [],
    });
    await settleWatch();

    expect(renderedText()).toContain('SRD');
    expect(renderedText()).toContain('42 objects read from the file');
  });

  /** The checksum gate means a re-upload of the same file starts no run — the page has to say so
   *  rather than reading as if the button did nothing (ADR 0019 §3). */
  it('reports when the file is unchanged since the last import, without starting a run', async () => {
    await settleLoad();
    await choose('module.json', '{}');

    importButton()?.click();
    httpTesting.expectOne('/api/v1/doors/import').flush({
      status: 'skipped',
      moduleRef: 'ref-1',
      moduleName: 'SRD',
      objects: 42,
      checksum: 'abc123',
      warnings: [],
    });
    await fixture.whenStable();
    fixture.detectChanges();

    expect(renderedText()).toContain('unchanged since its last import');
    // No run started, so nothing opens the SSE stream to watch — but a 'skipped' result still
    // refreshes the last-run card, which is a second round of the same two requests settleLoad
    // answers on initial load.
    await settleLoad();
  });

  it('shows the server sentence when a file is refused', async () => {
    await settleLoad();
    await choose('broken.json', 'not json');

    importButton()?.click();
    httpTesting.expectOne('/api/v1/doors/import').flush(
      { title: 'That file is not a DOORS export', detail: 'The file is not valid JSON.' },
      { status: 400, statusText: 'Bad Request' },
    );
    await fixture.whenStable();
    fixture.detectChanges();

    expect(renderedText()).toContain('The file is not valid JSON.');
  });

  /** The R8 visibility gate on a re-import (ADR 0019 §4) — a 404 the server explains in a full
   *  sentence, shown the same way any other refusal is. */
  it('shows the server sentence when the module is not visible to this account', async () => {
    await settleLoad();
    await choose('module.json', '{}');

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
