import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { JiraLinksDialog } from './jira-links-dialog';
import type { JiraLinkGraph } from './jira-links.model';

const SEED = 'c2NydW0tMQ';
const OTHER = 'c2NydW0tMg';
const STUB = 'c2NydW0tMTAw';

/**
 * Three issues: the one that was opened, one it relates to, and one stub it is a sub-task of.
 *
 * The stub is the case worth having in every fixture of this feature — a link to an issue outside
 * the configured projects is a fact about the issue being looked at, and the picture has to say so
 * rather than quietly stopping.
 */
const GRAPH: JiraLinkGraph = {
  seedRef: SEED,
  depth: 2,
  nodes: [
    {
      ref: SEED,
      key: 'SCRUM-1',
      typeName: 'Task',
      statusName: 'In Progress',
      summary: 'A first issue',
      unresolved: false,
      seed: true,
      truncatedNeighbours: 0,
    },
    {
      ref: OTHER,
      key: 'SCRUM-2',
      typeName: 'Bug',
      statusName: 'Done',
      summary: 'Thermal margins',
      unresolved: false,
      seed: false,
      truncatedNeighbours: 2,
    },
    {
      ref: STUB,
      key: 'SCRUM-100',
      typeName: null,
      statusName: null,
      summary: null,
      unresolved: true,
      seed: false,
      truncatedNeighbours: 0,
    },
  ],
  edges: [
    { source: SEED, target: OTHER, typeName: 'Relates', subTask: false },
    { source: SEED, target: STUB, typeName: null, subTask: true },
  ],
  truncated: true,
};

describe('JiraLinksDialog', () => {
  let fixture: ComponentFixture<JiraLinksDialog>;
  let httpTesting: HttpTestingController;

  const element = (): HTMLElement => fixture.nativeElement;
  const renderedText = (): string => element().textContent ?? '';

  /**
   * ELK runs in-thread under jsdom (there is no `Worker`), but it is still asynchronous — so the
   * diagram needs a turn of the event loop after the response before its nodes are in the DOM.
   */
  const settle = async (): Promise<void> => {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 40));
    fixture.detectChanges();
  };

  const answer = async (body: JiraLinkGraph = GRAPH): Promise<void> => {
    httpTesting
      .expectOne((request) => request.url === `/api/v1/jira/issues/${SEED}/graph`)
      .flush(body);
    await settle();
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [JiraLinksDialog],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideNoopAnimations(),
        { provide: MatDialogRef, useValue: { close: () => undefined } },
        { provide: MAT_DIALOG_DATA, useValue: { seedRef: SEED, seedKey: 'SCRUM-1' } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(JiraLinksDialog);
    httpTesting = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  afterEach(() => {
    httpTesting.verify();
  });

  it('asks for the opened issue at the default depth', () => {
    const request = httpTesting.expectOne(
      (candidate) => candidate.url === `/api/v1/jira/issues/${SEED}/graph`,
    );

    expect(request.request.params.get('depth')).toBe('2');
    request.flush(GRAPH);
  });

  /** The four things §13.2 asks a node to carry, on every node that has them. */
  it('draws each issue with its type, key, status and summary', async () => {
    await answer();

    const text = renderedText();
    expect(text).toContain('Task');
    expect(text).toContain('SCRUM-1');
    expect(text).toContain('In Progress');
    expect(text).toContain('A first issue');
    expect(text).toContain('Thermal margins');
  });

  /** Three nodes in, three boxes out — the layout places every node it is given. */
  it('places every issue it was sent', async () => {
    await answer();

    expect(element().querySelectorAll('.sec-jira-graph__node').length).toBe(3);
  });

  /**
   * The seed is named in words, not only in colour.
   *
   * The same rule the breakdown tree follows for the requirement it was opened for: a reader who
   * cannot see the tint still has to be able to find the issue they clicked.
   */
  it('says which node is the issue that was opened', async () => {
    await answer();

    const seed = element().querySelector('.sec-jira-graph__node--seed');
    expect(seed?.textContent).toContain('SCRUM-1');
    expect(seed?.textContent).toContain('the issue you opened');
  });

  /** A stub is drawn, and carries the same words `:__UNDEFINED` carries everywhere else (R5). */
  it('draws a link target that has not been imported, and says what it is', async () => {
    await answer();

    expect(renderedText()).toContain('SCRUM-100');
    expect(renderedText()).toContain('Not yet imported');
  });

  /**
   * A picture that stopped has to say so — twice, and at two scales.
   *
   * The banner says the diagram is incomplete; the badge says which issue has more. Neither is
   * inferable from an absence, which is the whole failure mode.
   */
  it('says when the picture is incomplete, and on which issue', async () => {
    await answer();

    expect(renderedText()).toContain('Some links are not drawn');
    expect(element().querySelector('.sec-jira-graph__more')?.textContent).toContain('+2');
  });

  it('asks again when the depth changes, and keeps the diagram meanwhile', async () => {
    await answer();

    const buttons = Array.from(
      element().querySelectorAll<HTMLButtonElement>('.sec-jira-links-dialog__depth'),
    );
    buttons[0].click();
    fixture.detectChanges();

    const request = httpTesting.expectOne(
      (candidate) => candidate.url === `/api/v1/jira/issues/${SEED}/graph`,
    );
    expect(request.request.params.get('depth')).toBe('1');
    // The previous picture is still on screen: a diagram that blinked out on every depth change
    // would make the control feel like it broke something.
    expect(renderedText()).toContain('SCRUM-1');

    request.flush({ ...GRAPH, depth: 1 });
    await settle();
  });

  it('reports a failure instead of an empty diagram', async () => {
    httpTesting
      .expectOne((candidate) => candidate.url === `/api/v1/jira/issues/${SEED}/graph`)
      .flush('nope', { status: 500, statusText: 'Server Error' });
    await settle();

    expect(renderedText()).toContain("Couldn't load the related issues");
  });
});
