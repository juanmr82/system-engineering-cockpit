import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { WindchillSettings } from './windchill-settings';

const HEALTH = { configured: true, host: 'https://windchill.example.com/Windchill' };

/**
 * A stand-in for the browser's EventSource, which jsdom does not implement.
 *
 * Not a mock of convenience: without it `new EventSource(...)` is a ReferenceError the moment the
 * page starts watching the run it just started, and every assertion after the upload would be
 * asserting against a page that had thrown. `import-run-store.spec.ts` carries the same double.
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
 * The Windchill settings page.
 *
 * What is worth testing here is the shape of the one act it offers: choosing a file writes nothing,
 * pressing Import sends the file's text once, and the two things the answer can say that a person
 * must not miss — the file was refused, or the file was only one page.
 *
 * jsdom's `File` has `text()`, so the read is real rather than stubbed.
 */
describe('WindchillSettings', () => {
  let fixture: ComponentFixture<WindchillSettings>;
  let httpTesting: HttpTestingController;

  const renderedText = (): string => fixture.nativeElement.textContent;

  const importButton = (): HTMLButtonElement | null =>
    Array.from(fixture.nativeElement.querySelectorAll('button')).find((button) =>
      (button as HTMLElement).textContent?.includes('Import documents'),
    ) as HTMLButtonElement | null;

  const fileInput = (): HTMLInputElement =>
    fixture.nativeElement.querySelector('input[type="file"]');

  /** Answers the two requests the page makes on load, so a spec starts from a settled page. */
  const settleLoad = async (): Promise<void> => {
    httpTesting.expectOne('/api/v1/windchill/health').flush(HEALTH);
    httpTesting
      .match((request) => request.url.startsWith('/api/v1/import/runs'))
      .forEach((request) => request.flush([]));
    httpTesting
      .match((request) => request.url === '/api/v1/import/importers')
      .forEach((request) => request.flush([]));
    await fixture.whenStable();
    fixture.detectChanges();
  };

  /** Chooses a file the way the browser does, and waits for the page to have read it. */
  const choose = async (name: string, contents: string): Promise<void> => {
    const input = fileInput();
    const file = new File([contents], name, { type: 'application/json' });
    Object.defineProperty(input, 'files', { value: [file], configurable: true });
    input.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    fixture.detectChanges();
  };

  /**
   * Flushes the run resource the page reads when it starts watching.
   *
   * Issued only after the upload's promise has settled, which is why every caller awaits stability
   * first — matching before that finds nothing and leaves the request open for `verify()`.
   */
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
      imports: [WindchillSettings],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideNoopAnimations(),
        provideRouter([]),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(WindchillSettings);
    httpTesting = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  afterEach(() => {
    httpTesting.verify();
  });

  it('shows the configured address, which is not a secret', async () => {
    await settleLoad();

    expect(renderedText()).toContain('windchill.example.com');
  });

  it('says what an absent address costs, which is only the link out', async () => {
    httpTesting.expectOne('/api/v1/windchill/health').flush({ configured: false, host: '' });
    httpTesting
      .match((request) => request.url.startsWith('/api/v1/import/'))
      .forEach((request) => request.flush([]));
    await fixture.whenStable();
    fixture.detectChanges();

    expect(renderedText()).toContain('Not configured');
    expect(renderedText()).toContain('only the link out');
  });

  /** Choosing is not importing (R7): nothing has been sent until the button is pressed. */
  it('sends nothing when a file is merely chosen', async () => {
    await settleLoad();

    await choose('export.json', '{"value":[]}');

    expect(renderedText()).toContain('export.json');
    httpTesting.expectNone('/api/v1/windchill/import');
  });

  it('cannot import until a file has been chosen', async () => {
    await settleLoad();

    expect(importButton()?.disabled).toBe(true);

    await choose('export.json', '{"value":[]}');

    expect(importButton()?.disabled).toBe(false);
  });

  it('posts the file text once and reports what was read', async () => {
    await settleLoad();
    await choose('export.json', '{"value":[{"ID":"OR:1"}]}');

    importButton()?.click();
    const request = httpTesting.expectOne('/api/v1/windchill/import');
    expect(request.request.body).toBe('{"value":[{"ID":"OR:1"}]}');

    request.flush({ runId: 'run-1', documents: 2, paged: false, warnings: [] });
    await settleWatch();

    expect(renderedText()).toContain('2 documents read from the file');
  });

  /**
   * The refusal is shown in the **server's** words.
   *
   * It knows which line of the file was wrong and the browser does not, so replacing its sentence
   * with one of our own would lose the only useful part of the answer.
   */
  it('shows the server sentence when a file is refused', async () => {
    await settleLoad();
    await choose('broken.json', "{'value': []}");

    importButton()?.click();
    httpTesting
      .expectOne('/api/v1/windchill/import')
      .flush(
        { title: 'That file is not a Windchill export', detail: 'The file is not valid JSON.' },
        { status: 400, statusText: 'Bad Request' },
      );
    await fixture.whenStable();
    fixture.detectChanges();

    expect(renderedText()).toContain('The file is not valid JSON.');
  });

  /**
   * A paged export imports *and* is incomplete, and the sweep is about to treat it as everything.
   * That is the one warning this page must not bury.
   */
  it('warns when the export was only one page', async () => {
    await settleLoad();
    await choose('page1.json', '{"value":[]}');

    importButton()?.click();
    httpTesting
      .expectOne('/api/v1/windchill/import')
      .flush({ runId: 'run-1', documents: 1, paged: true, warnings: [] });
    await settleWatch();

    expect(renderedText()).toContain('one page of several');
  });
});
