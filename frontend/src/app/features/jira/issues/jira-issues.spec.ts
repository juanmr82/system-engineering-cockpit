import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { flushGridFrames } from '../../../core/grid/grid-testing';
import { JiraIssues, formatCellValue } from './jira-issues';
import type { JiraConnection, JiraIssues as JiraIssuesResponse } from '../jira.model';

const CONNECTED: JiraConnection = {
  configured: true,
  host: 'https://jira.example.com',
  platform: 'datacenter',
};

/**
 * Two rows whose columns are the two fixed ones plus three the admin selected.
 *
 * The values deliberately cover every shape a JIRA field can arrive as — a string, a number, a
 * list, and a path one row does not carry at all — because "renders blank rather than erroring"
 * is a rule the design doc states (§6.4) and nothing else enforces.
 */
const ISSUES: JiraIssuesResponse = {
  columns: [
    { path: 'key', label: 'Key', fixed: true },
    { path: 'issuetype.name', label: 'Issue type', fixed: true },
    { path: 'status.name', label: 'Status', fixed: false },
    { path: 'customfield_10032', label: 'Story Points', fixed: false },
    { path: 'components.name', label: 'Component › name', fixed: false },
  ],
  rows: [
    {
      ref: 'amlyYTppc3N1ZTpQUk9KLTE',
      key: 'PROJ-1',
      issueType: 'Bug',
      values: {
        'status.name': 'In Progress',
        customfield_10032: 5,
        'components.name': ['Avionics', 'Power'],
      },
    },
    {
      ref: 'amlyYTppc3N1ZTpQUk9KLTI',
      key: 'PROJ-2',
      issueType: 'Story',
      // No story points and no components: this issue type does not define them.
      values: { 'status.name': 'Done' },
    },
  ],
  total: 2,
  offset: 0,
  limit: 100,
};

describe('formatCellValue', () => {
  it('renders an absent value as blank rather than as a word', () => {
    // §6.4: a missing path on a given issue renders blank, it is not an error.
    expect(formatCellValue(null)).toBe('');
    expect(formatCellValue(undefined)).toBe('');
  });

  it('joins a list instead of stacking it', () => {
    // A chip per element in an auto-height cell makes a two-element row twice the height of its
    // neighbours for no information gained.
    expect(formatCellValue(['Avionics', 'Power'])).toBe('Avionics, Power');
    expect(formatCellValue([])).toBe('');
  });

  it('keeps numbers and booleans readable', () => {
    expect(formatCellValue(5)).toBe('5');
    expect(formatCellValue(0)).toBe('0');
    expect(formatCellValue(true)).toBe('Yes');
    expect(formatCellValue(false)).toBe('No');
  });
});

describe('JiraIssues', () => {
  let fixture: ComponentFixture<JiraIssues>;
  let httpTesting: HttpTestingController;

  const renderedText = (): string => fixture.nativeElement.textContent;

  const settle = async (): Promise<void> => {
    fixture.detectChanges();
    await fixture.whenStable();
    // Angular being stable is not the grid having drawn — see flushGridFrames.
    await flushGridFrames();
    fixture.detectChanges();
  };

  /**
   * Answers whatever the service has in flight.
   *
   * `JiraApiService` is a root singleton holding three resources, so mounting any component that
   * injects it starts all three — including `projects`, which this view does not render. Matching
   * rather than asserting one request keeps the spec about this component instead of about the
   * service's fan-out, and survives a reload firing a second GET.
   */
  const answer = async (connection: JiraConnection, issues?: JiraIssuesResponse) => {
    httpTesting
      .match((request) => request.url === '/api/v1/jira/connection')
      .forEach((request) => request.flush(connection));
    httpTesting
      .match((request) => request.url === '/api/v1/jira/projects')
      .forEach((request) => request.flush({ projects: [], available: [] }));
    // Always answered, even when this test does not care: the service starts it regardless of
    // whether JIRA is configured, and an unanswered request fails verify() in afterEach — which
    // then cascades into every test after it.
    httpTesting
      .match((request) => request.url.startsWith('/api/v1/jira/issues'))
      .forEach((request) =>
        request.flush(issues ?? { columns: [], rows: [], total: 0, offset: 0, limit: 100 }),
      );
    await settle();
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [JiraIssues],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideNoopAnimations(),
        provideRouter([]),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(JiraIssues);
    httpTesting = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  afterEach(() => {
    httpTesting.verify();
  });

  it('says JIRA is not connected rather than offering an import that cannot work', async () => {
    await answer({ configured: false, host: '', platform: 'datacenter' });

    expect(renderedText()).toContain('JIRA is not connected');
    // The button would answer 503, so it is not drawn at all.
    expect(fixture.nativeElement.querySelector('.sec-jira-issues__import')).toBeNull();
  });

  it('builds its columns from the server, not from a compiled list', async () => {
    await answer(CONNECTED, ISSUES);

    // Every label came off the wire. The component never maps a field id to wording of its own —
    // that is resolved from the JIRA catalogue server-side (R5).
    const text = renderedText();
    expect(text).toContain('Story Points');
    expect(text).toContain('Status');
    // And no internal name reaches the screen.
    expect(text).not.toContain('customfield_10032');
  });

  it('reads a dotted path out of the value bag rather than through a property path', async () => {
    await answer(CONNECTED, ISSUES);

    // The regression this guards: ag-grid reads a dot in `field` as a property path, so
    // `field: 'status.name'` would look for row.status.name, find nothing, and render blank with
    // no error at all.
    const text = renderedText();
    expect(text).toContain('In Progress');
    expect(text).toContain('Avionics, Power');
  });

  it('draws a row that is missing a column without complaint', async () => {
    await answer(CONNECTED, ISSUES);

    // PROJ-2 carries no story points and no components; it is still a row.
    expect(renderedText()).toContain('PROJ-2');
    expect(renderedText()).toContain('Done');
  });

  it('reports the range it is showing', async () => {
    await answer(CONNECTED, ISSUES);
    expect(renderedText()).toContain('1–2 of 2');
  });

  it('offers an empty state, not an empty table, before the first import', async () => {
    await answer(CONNECTED, { ...ISSUES, rows: [], total: 0 });

    expect(renderedText()).toContain('No issues imported yet');
    expect(renderedText()).toContain('Import issues');
  });

  it('reloads the table and shows the report when an import finishes', async () => {
    await answer(CONNECTED, ISSUES);

    fixture.nativeElement.querySelector('.sec-jira-issues__import').click();
    await settle();

    httpTesting.expectOne((r) => r.url === '/api/v1/jira/import').flush({
      startedAt: '2026-08-10T10:00:00Z',
      durationMs: 4200,
      projects: ['PROJ'],
      issuesSeen: 2,
      issuesCreated: 1,
      issuesUpdated: 1,
      issuesDeleted: 3,
      issueTypes: 2,
      fieldsInCatalog: 40,
      fieldsAdded: [],
      fieldsRemoved: [],
      linksCreated: 1,
      linksPruned: 0,
      hierarchyPruned: 0,
      placeholdersCreated: 1,
      placeholdersCollected: 0,
      warnings: [],
    });
    await settle();

    // The table is reloaded before the dialog opens, so the numbers the report quotes and the
    // rows behind it are the same run's.
    const reloads = httpTesting.match((r) => r.url.startsWith('/api/v1/jira/issues'));
    expect(reloads.length).toBeGreaterThan(0);
    reloads.forEach((request) => request.flush(ISSUES));
    await settle();

    const dialog = document.querySelector('sec-import-report-dialog');
    expect(dialog).not.toBeNull();
    expect(dialog?.textContent).toContain('Import finished');
    // Removals are the number a reader most needs to have seen.
    expect(dialog?.textContent).toContain('3');
  });

  it('keeps the failure inline when the import is refused', async () => {
    await answer(CONNECTED, ISSUES);

    fixture.nativeElement.querySelector('.sec-jira-issues__import').click();
    await settle();

    httpTesting
      .expectOne((r) => r.url === '/api/v1/jira/import')
      .flush(
        { title: 'No projects selected', detail: 'Add at least one JIRA project before importing.' },
        { status: 400, statusText: 'Bad Request' },
      );
    await settle();

    // The server's own sentence, not a generic one — and the table is untouched.
    expect(renderedText()).toContain('Add at least one JIRA project before importing.');
    expect(renderedText()).toContain('PROJ-1');
  });
});
