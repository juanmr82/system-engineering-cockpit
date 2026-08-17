import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import type { ImportSchedule } from '../../../core/import/import.model';
import { JiraSettings } from './jira-settings';
import type { JiraHealth, JiraProject } from './jira-settings.model';

const HEALTH: JiraHealth = {
  configured: true,
  reachable: true,
  user: 'Juan Reina',
  message: 'Connected to JIRA as Juan Reina.',
  host: 'https://jira.example.com',
};

const PROJECTS: JiraProject[] = [
  { key: 'SCRUM', name: 'Scrum board' },
  { key: 'OTS', name: 'Off the shelf' },
];

const SCHEDULE: ImportSchedule = {
  scheduled: true,
  nextRunAt: '2026-08-16T18:00:00Z',
  intervalMinutes: 60,
};

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
   * Five requests: two resources, and the three the import section needs — history, importers and
   * the schedule. They are matched by URL rather than in order, because the order they are issued
   * in is not part of the contract.
   */
  const answer = async (schedule: ImportSchedule = SCHEDULE): Promise<void> => {
    httpTesting.expectOne('/api/v1/jira/health').flush(HEALTH);
    httpTesting.expectOne('/api/v1/jira/projects').flush(PROJECTS);
    httpTesting.expectOne('/api/v1/jira/columns').flush([]);
    httpTesting
      .expectOne((request) => request.url.startsWith('/api/v1/import/runs'))
      .flush([]);
    httpTesting.expectOne('/api/v1/import/importers').flush([]);
    httpTesting.expectOne('/api/v1/import/jira/schedule').flush(schedule);
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

  /** ADR 0018: a diagnostic, not a picker — every project the token can see is listed. */
  it('lists the projects the token can currently see', async () => {
    await answer();

    expect(renderedText()).toContain('SCRUM');
    expect(renderedText()).toContain('OTS');
  });

  /** There is no project gate any more (ADR 0018): an import is always available to run. */
  it('the import button is never disabled for lack of configured projects', async () => {
    await answer();

    const start = button('Import JIRA issues');
    expect(start?.disabled).toBe(false);
  });

  it('shows when the next scheduled import will run', async () => {
    await answer();

    expect(renderedText()).toContain('Next scheduled import');
    expect(renderedText()).toContain('2026-08-16T18:00:00Z');
  });

  it('says so when no periodic import is configured', async () => {
    await answer({ scheduled: false, nextRunAt: null, intervalMinutes: null });

    expect(renderedText()).toContain('No periodic import is configured');
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
