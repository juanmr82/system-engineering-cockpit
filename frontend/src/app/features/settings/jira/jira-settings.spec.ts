import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { JiraSettings } from './jira-settings';
import type { JiraHealth, JiraProject, JiraProjectSettings } from './jira-settings.model';

const HEALTH: JiraHealth = {
  configured: true,
  reachable: true,
  user: 'Juan Reina',
  message: 'Connected to JIRA as Juan Reina.',
  host: 'https://jira.example.com',
};

const SETTINGS: JiraProjectSettings = {
  projectKeys: ['SCRUM', 'OTS'],
  jql: 'project in ("SCRUM","OTS") ORDER BY key ASC',
};

const PROJECTS: JiraProject[] = [
  { key: 'SCRUM', name: 'Scrum board' },
  { key: 'OTS', name: 'Off the shelf' },
  { key: 'NEW', name: 'A third project' },
];

describe('JiraSettings', () => {
  let fixture: ComponentFixture<JiraSettings>;
  let httpTesting: HttpTestingController;

  const element = (): HTMLElement => fixture.nativeElement;
  const renderedText = (): string => element().textContent ?? '';

  const settle = async (): Promise<void> => {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  };

  /**
   * Answer everything the page asks for on load.
   *
   * Five requests: three resources and the two the import section needs. They are matched by URL
   * rather than in order, because the order they are issued in is not part of the contract.
   */
  const answer = async (settings: JiraProjectSettings = SETTINGS): Promise<void> => {
    httpTesting.expectOne('/api/v1/jira/health').flush(HEALTH);
    httpTesting.expectOne('/api/v1/jira/settings').flush(settings);
    httpTesting.expectOne('/api/v1/jira/projects').flush(PROJECTS);
    httpTesting.expectOne('/api/v1/jira/columns').flush([]);
    httpTesting
      .expectOne((request) => request.url.startsWith('/api/v1/import/runs'))
      .flush([]);
    httpTesting.expectOne('/api/v1/import/importers').flush([]);
    await settle();
  };

  const button = (label: string): HTMLButtonElement | undefined =>
    Array.from(element().querySelectorAll('button')).find(
      (candidate) => candidate.textContent?.trim() === label,
    );

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [JiraSettings],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideNoopAnimations(),
        provideRouter([]),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(JiraSettings);
    httpTesting = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  afterEach(() => {
    httpTesting.verify();
  });

  it('reports the connection without ever naming the token', async () => {
    await answer();

    expect(renderedText()).toContain('https://jira.example.com');
    expect(renderedText()).toContain('Connected to JIRA as Juan Reina.');
    // The credential lives in application.yaml and never reaches the browser (spec §3).
    expect(renderedText().toLowerCase()).not.toContain('token');
  });

  /** The single best debugging aid in the feature: exactly what the next import will send. */
  it('shows the query the configured projects produce', async () => {
    await answer();

    expect(renderedText()).toContain('project in ("SCRUM","OTS") ORDER BY key ASC');
  });

  /**
   * One gesture, one request, one transaction (R7).
   *
   * There is no Save button for the chip list and no buffer behind it, which is what keeps this
   * page free of an exit guard: what a user saw happen is what was written.
   */
  it('saves a removed project immediately, and says what it will cost', async () => {
    await answer();

    element().querySelector<HTMLButtonElement>('[aria-label="Remove OTS"]')?.click();
    await settle();

    const request = httpTesting.expectOne(
      (candidate) => candidate.url === '/api/v1/jira/settings' && candidate.method === 'PUT',
    );
    expect(request.request.body).toEqual({ projectKeys: ['SCRUM'] });

    request.flush({ projectKeys: ['SCRUM'], jql: 'project in ("SCRUM") ORDER BY key ASC' });
    await settle();

    // The server owns the preview, so the new state is read back rather than assembled here.
    httpTesting
      .expectOne('/api/v1/jira/settings')
      .flush({ projectKeys: ['SCRUM'], jql: 'project in ("SCRUM") ORDER BY key ASC' });
    await settle();

    expect(renderedText()).toContain('Issues from OTS will be deleted from the cockpit');
  });

  /**
   * An import across a whole JIRA instance is a thing this application never does.
   *
   * The button says why it is disabled rather than simply being dead — the tooltip is the whole
   * difference between a control a user can act on and one they file a bug about.
   */
  it('will not start an import with no projects configured', async () => {
    await answer({ projectKeys: [], jql: null });

    const start = button('Import JIRA issues');
    expect(start?.disabled).toBe(true);
    expect(start?.getAttribute('mattooltip') ?? renderedText()).toContain('project');
  });

  it('starts an import and reports it as running', async () => {
    await answer();

    button('Import JIRA issues')?.click();
    await settle();

    const start = httpTesting.expectOne(
      (candidate) => candidate.url === '/api/v1/import/jira/runs' && candidate.method === 'POST',
    );
    start.flush({ runId: 'run-1' });
    await settle();

    // Started, then watched: the run resource is read before anything subscribes, because an event
    // stream is not a history (spec §11.4).
    httpTesting.expectOne('/api/v1/import/runs/run-1').flush({
      runId: 'run-1',
      importerId: 'jira',
      status: 'SUCCEEDED',
      startedAt: '2026-08-12T06:00:00Z',
      finishedAt: '2026-08-12T06:00:30Z',
      phase: null,
      phases: [],
      percent: 100,
      current: 0,
      total: 0,
      params: {},
      counters: { issuesSeen: 9 },
      warnings: [],
      error: null,
      log: [],
    });
    await settle();

    // A finished run leaves nothing to subscribe to: the run resource has already said everything
    // there is to say, so no stream is opened (which is also why this spec needs no EventSource).
    expect(renderedText()).not.toContain('Importing');
  });
});
