import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { settleGrid } from '../../../core/grid/grid-testing';
import { JiraIssues } from './jira-issues';
import type { JiraIssuesPage } from './jira-issues.model';

/**
 * A page of five issues, one of them a stub.
 *
 * The keys are 1, 2, 10 and 100 because that is the set a string sort gets wrong — and the server
 * is what sorts them, so the fixture arrives in the order the table must show. A spec that fed the
 * grid unsorted rows and asserted a sorted result would be asserting that the grid re-sorts, which
 * is exactly the behaviour this view turns off.
 */
const PAGE: JiraIssuesPage = {
  page: 0,
  size: 50,
  total: 5,
  columns: [],
  rows: [
    {
      ref: 'cmVmLTM',
      key: 'OTS-3',
      name: 'OTS-3: Another project',
      issueTypeName: 'Bug',
      browseUrl: 'https://jira.example.com/browse/OTS-3',
      unresolved: false,
      values: {},
    },
    {
      ref: 'cmVmLTE',
      key: 'SCRUM-1',
      name: 'SCRUM-1: A first issue',
      issueTypeName: 'Task',
      browseUrl: 'https://jira.example.com/browse/SCRUM-1',
      unresolved: false,
      values: {},
    },
    {
      ref: 'cmVmLTI',
      key: 'SCRUM-2',
      name: 'SCRUM-2: Thermal margins',
      issueTypeName: 'Task',
      browseUrl: 'https://jira.example.com/browse/SCRUM-2',
      unresolved: false,
      values: {},
    },
    {
      ref: 'cmVmLTEw',
      key: 'SCRUM-10',
      name: 'SCRUM-10: Ten',
      issueTypeName: 'Task',
      browseUrl: 'https://jira.example.com/browse/SCRUM-10',
      unresolved: false,
      values: {},
    },
    // A link target outside the configured projects: no type, and it says what it is.
    {
      ref: 'cmVmLTEwMA',
      key: 'SCRUM-100',
      name: '<unresolved SCRUM-100>',
      issueTypeName: null,
      browseUrl: 'https://jira.example.com/browse/SCRUM-100',
      unresolved: true,
      values: {},
    },
  ],
};

const EMPTY: JiraIssuesPage = { page: 0, size: 50, total: 0, columns: [], rows: [] };

/**
 * The same page, with columns configured.
 *
 * Four shapes in three columns: a scalar, a list, a value the issue does not carry, and a column
 * whose field JIRA has dropped. They are one fixture because they are one response — the headers
 * and the cells arrive together, which is the property the table depends on.
 */
const WITH_COLUMNS: JiraIssuesPage = {
  ...PAGE,
  columns: [
    { fieldId: 'summary', name: 'Summary', schemaType: 'string', sortable: true, stale: false },
    { fieldId: 'labels', name: 'Labels', schemaType: 'array', sortable: false, stale: false },
    {
      fieldId: 'customfield_999',
      name: 'customfield_999',
      schemaType: null,
      sortable: false,
      stale: true,
    },
  ],
  rows: PAGE.rows.map((row, index) => ({
    ...row,
    values:
      index === 0
        ? { summary: 'A first issue', labels: ['thermal', 'margins', 'v2', 'extra'] }
        : { summary: null, labels: [] },
  })),
};

describe('JiraIssues', () => {
  let fixture: ComponentFixture<JiraIssues>;
  let httpTesting: HttpTestingController;

  const renderedText = (): string => fixture.nativeElement.textContent;

  // Angular being stable is not the grid having drawn, and the number of frames it takes is not
  // fixed — a cell renderer mounts on a later one. settleGrid waits for the DOM to stop changing.
  const settle = (): Promise<void> => settleGrid(fixture);

  /** The one outstanding request, so every assertion about the URL is about a real one. */
  const expectRequest = () => {
    const pending = httpTesting.match((request) => request.url === '/api/v1/jira/issues');
    expect(pending.length).toBe(1);
    return pending[0];
  };

  const answerWith = async (body: JiraIssuesPage): Promise<void> => {
    expectRequest().flush(body);
    await settle();
  };

  const keys = (): string[] =>
    Array.from(fixture.nativeElement.querySelectorAll('.sec-jira-key')).map(
      (cell) => (cell as HTMLElement).textContent?.trim() ?? '',
    );

  const searchBox = (): HTMLInputElement =>
    fixture.nativeElement.querySelector('input[type="text"]');

  /** Types a term and returns once the debounce has turned it into a request — but before it is answered. */
  const search = async (term: string): Promise<void> => {
    const input = searchBox();
    input.value = term;
    input.dispatchEvent(new Event('input'));
    // The debounce timer starts on the next change detection, not on the event: this TestBed has
    // no auto-detection, so without this pass the 250ms is counted from whenever the next one
    // happens, and the wait below expires with the request still unsent.
    fixture.detectChanges();
    // The search is debounced by 250ms; a shorter wait asserts against the previous request.
    await new Promise((resolve) => setTimeout(resolve, 300));
    // Deliberately not `settle()`: the request this just started is in flight, and `whenStable()`
    // does not resolve while an httpResource is loading — it would time the spec out.
    fixture.detectChanges();
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [JiraIssues],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations()],
    }).compileComponents();

    fixture = TestBed.createComponent(JiraIssues);
    httpTesting = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  afterEach(() => {
    httpTesting.verify();
  });

  it('asks for the first page in the server default order', () => {
    const request = expectRequest();

    expect(request.request.params.get('page')).toBe('0');
    expect(request.request.params.get('sort')).toBe('key');
    expect(request.request.params.get('dir')).toBe('asc');
    // Not sent at all rather than sent empty: a URL carrying `q=` looks like it is searching.
    expect(request.request.params.has('q')).toBe(false);
  });

  it('renders the rows in the order the server sent them', async () => {
    await answerWith(PAGE);

    expect(keys()).toEqual(['OTS-3', 'SCRUM-1', 'SCRUM-2', 'SCRUM-10', 'SCRUM-100']);
  });

  /**
   * A stub is a row like any other and says what it is, in words.
   *
   * `unresolved` is a state channel, never display text (R5) — this assertion is what pins the
   * wording to the client, and the wording is the same *Not yet imported* the DOORS placeholder
   * carries, because it is the same fact about a different source.
   */
  it('says when a row is a placeholder rather than an imported issue', async () => {
    await answerWith(PAGE);

    expect(renderedText()).toContain('Not yet imported');
    // And says it once: only the stub is one.
    expect(renderedText().match(/Not yet imported/g)?.length).toBe(1);
  });

  /**
   * The link goes to the browse URL the server derived, never to the issue's stored identity —
   * `self` is an API URL that answers with raw JSON (spec §13.2).
   */
  it('links each row to its JIRA browse page, in a new tab', async () => {
    await answerWith(PAGE);

    const link: HTMLAnchorElement = fixture.nativeElement.querySelector(
      '[aria-label="Open SCRUM-1 in JIRA"]',
    );

    expect(link.getAttribute('href')).toBe('https://jira.example.com/browse/SCRUM-1');
    expect(link.getAttribute('target')).toBe('_blank');
    // Without noopener the opened page can reach back through window.opener.
    expect(link.getAttribute('rel')).toBe('noopener noreferrer');
  });

  it('sends the search term once the typing settles, and returns to the first page', async () => {
    await answerWith(PAGE);
    await search('thermal');

    const request = expectRequest();
    expect(request.request.params.get('q')).toBe('thermal');
    expect(request.request.params.get('page')).toBe('0');

    request.flush({ ...PAGE, total: 1, rows: [PAGE.rows[2]] });
    await settle();
    expect(keys()).toEqual(['SCRUM-2']);
  });

  /**
   * The search box is the one piece of UI a reload must not take away.
   *
   * Every keystroke starts a request, and a table that unmounts its toolbar while one is in flight
   * takes the focus and the caret with it — so the next character is typed into nothing. This is
   * the trap the handover records as "a component inside an `@if` on a resource unmounts while that
   * resource reloads"; the assertion is identity, not text, because a re-created input looks
   * identical and behaves nothing like the same one.
   */
  it('keeps the search box, and its focus, while its own request is in flight', async () => {
    await answerWith(PAGE);

    const input = searchBox();
    input.focus();
    await search('thermal');

    expect(searchBox()).toBe(input);
    expect(document.activeElement).toBe(input);
    expect(input.value).toBe('thermal');

    expectRequest().flush({ ...PAGE, total: 1, rows: [PAGE.rows[2]] });
    await settle();

    expect(searchBox()).toBe(input);
  });

  /**
   * Two empties that need different words, and they are two tests because they are two views.
   *
   * Nothing imported is an invitation to import, so there is no toolbar at all — nothing to search.
   * Nothing *matching* keeps the toolbar, because the term that produced it is in the box and
   * retyping is the way out. The filtered total is zero in both, so the search term is the only
   * thing separating them, and telling a user to run an import because their search matched nothing
   * is the failure these two exist to prevent.
   */
  it('invites an import when the graph holds no issues', async () => {
    await answerWith(EMPTY);

    expect(renderedText()).toContain('No JIRA issues imported yet');
    expect(renderedText()).not.toContain('No issues match');
    // Nothing to filter, so nothing to filter with.
    expect(searchBox()).toBeNull();
  });

  it('invites a retype when a search matches nothing', async () => {
    await answerWith(PAGE);
    await search('nothing');
    expectRequest().flush(EMPTY);
    await settle();

    expect(renderedText()).toContain('No issues match "nothing"');
    expect(renderedText()).not.toContain('No JIRA issues imported yet');
    // The box keeps the term, because retyping it is the way out of this state.
    expect(searchBox().value).toBe('nothing');
  });

  it('asks for the page the paginator moved to', async () => {
    await answerWith({ ...PAGE, total: 120 });

    const next: HTMLButtonElement = fixture.nativeElement.querySelector(
      '.mat-mdc-paginator-navigation-next',
    );
    next.click();
    // Not `settle()`: the click has already started the request, and `whenStable()` does not
    // resolve while one is in flight.
    fixture.detectChanges();

    const request = expectRequest();
    expect(request.request.params.get('page')).toBe('1');

    request.flush({ ...PAGE, total: 120 });
    await settle();
  });

  /**
   * The configured columns come from the response and are drawn between Key and the link.
   *
   * The fixed three are the client's own and are never described by the server, which is what makes
   * them impossible to hide; everything else here is the user's choice.
   */
  it('draws the columns the server configured, in order', async () => {
    await answerWith(WITH_COLUMNS);

    const headers = Array.from(
      fixture.nativeElement.querySelectorAll('.ag-header-cell-text'),
    ).map((cell) => (cell as HTMLElement).textContent?.trim());

    expect(headers).toEqual(['Type', 'Key', 'Summary', 'Labels', 'customfield_999', '']);
  });

  /**
   * Three shapes, three renderings, and none of them is the word "null".
   *
   * An em-dash says the issue does not carry the field; a list says its values and how many it did
   * not show. A cell that printed `null`, or that was simply blank, would leave a reader unable to
   * tell an absent value from a broken column.
   */
  it('renders a value, a list and an absence differently', async () => {
    await answerWith(WITH_COLUMNS);

    const text = renderedText();

    expect(text).toContain('A first issue');
    expect(text).toContain('thermal');
    // Three chips and a count, never four chips.
    expect(text).toContain('+1');
    expect(text).not.toContain('null');
    expect(fixture.nativeElement.querySelector('.sec-jira-value--empty')).toBeTruthy();
  });

  it('offers a retry when the request fails, and does not pretend the table is empty', async () => {
    expectRequest().flush('nope', { status: 500, statusText: 'Server Error' });
    await settle();

    expect(renderedText()).toContain("Couldn't load issues");
    expect(renderedText()).not.toContain('No JIRA issues imported yet');
  });
});

