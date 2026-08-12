import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { settleGrid } from '../../../core/grid/grid-testing';
import { WindchillDocuments } from './windchill-documents';
import type { WindchillDocumentRow, WindchillDocuments as Payload } from './windchill-documents.model';

/**
 * Five documents: one number with three versions, one with two, one on its own.
 *
 * They arrive **in the server's order** — number ascending, newest version first — because that is
 * what the endpoint promises and what the grouping is derived from. A fixture in some other order
 * would be testing that the view re-sorts, which is exactly what it does not do on load.
 */
const ROWS: readonly WindchillDocumentRow[] = [
  row('a3', '/Docs/Reports', 'Quartalsbericht', 'N-A', '03 [1]', 'In work'),
  row('a2', '/Docs/Reports', 'Quartalsbericht', 'N-A', '02 [2]', 'Released'),
  row('a1', '/Docs/Reports', 'Quartalsbericht', 'N-A', '01 [2]', 'Released'),
  row('b2', '/Docs/Lists', 'Unterauftragnehmer', 'N-B', '02 [1]', 'Released'),
  row('b1', '/Docs/Lists', 'Unterauftragnehmer', 'N-B', '01 [1]', 'Released'),
  row('c1', '/Docs/Plans', 'Projektplan', 'N-C', '01 [1]', 'In work'),
];

const PAYLOAD: Payload = {
  rows: ROWS,
  total: ROWS.length,
  truncated: false,
  hostConfigured: true,
};

const EMPTY: Payload = { rows: [], total: 0, truncated: false, hostConfigured: true };

function row(
  ref: string,
  folderLocation: string,
  name: string,
  number: string,
  version: string,
  state: string,
): WindchillDocumentRow {
  return {
    ref,
    folderLocation,
    name,
    number,
    version,
    state,
    browseUrl: `https://windchill.example.com/Windchill/app/#ptc1/tcomp/infoPage?oid=OR:${ref}`,
  };
}

describe('WindchillDocuments', () => {
  let fixture: ComponentFixture<WindchillDocuments>;
  let httpTesting: HttpTestingController;

  const renderedText = (): string => fixture.nativeElement.textContent;

  // Angular being stable is not the grid having drawn, and the number of frames it takes is not
  // fixed — a cell renderer mounts on a later one. settleGrid waits for the DOM to stop changing.
  const settle = (): Promise<void> => settleGrid(fixture);

  const answerWith = async (body: Payload): Promise<void> => {
    const pending = httpTesting.match((request) => request.url === '/api/v1/windchill/documents');
    expect(pending.length).toBe(1);
    pending[0].flush(body);
    await settle();
  };

  /** Every drawn row, in order, as `<number> <version>` — a group header has no version. */
  const drawnRows = (): string[] =>
    Array.from(fixture.nativeElement.querySelectorAll('[role="row"]'))
      .map((element) => (element as HTMLElement).textContent?.replace(/\s+/g, ' ').trim() ?? '')
      .filter((text) => text.length > 0);

  const groupHeaders = (): HTMLElement[] =>
    Array.from(fixture.nativeElement.querySelectorAll('.sec-windchill-group'));

  const searchBox = (): HTMLInputElement =>
    fixture.nativeElement.querySelector('input[type="text"]');

  /**
   * Types a term and lets the view redraw.
   *
   * No wait for a debounce, because there is none: nothing is fetched, so a keystroke is one pass
   * over an array. That absence is the point of the design and is asserted below.
   */
  const search = async (term: string): Promise<void> => {
    const input = searchBox();
    input.value = term;
    input.dispatchEvent(new Event('input'));
    await settle();
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [WindchillDocuments],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideNoopAnimations(),
        provideRouter([]),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(WindchillDocuments);
    httpTesting = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  afterEach(() => {
    httpTesting.verify();
  });

  // -- grouping -------------------------------------------------------------------------------

  /** A number with several versions gets a header; a number with one does not. */
  it('draws a header over every document that has more than one version', async () => {
    await answerWith(PAYLOAD);

    const headers = groupHeaders();

    expect(headers.length).toBe(2);
    expect(headers[0].textContent).toContain('3 versions');
    expect(headers[1].textContent).toContain('2 versions');
    // N-C has one version and therefore no header — a header is a finding aid for rows that are
    // otherwise indistinguishable, and one row is not that.
    expect(headers.map((element) => element.textContent).join(' ')).not.toContain('1 versions');
  });

  /** The header carries no version and no state: they are what its rows disagree about. */
  it('leaves the version and state cells of a header empty', async () => {
    await answerWith(PAYLOAD);

    const header = groupHeaders()[0].closest('[role="row"]') as HTMLElement;

    expect(header.textContent).toContain('N-A');
    expect(header.textContent).toContain('/Docs/Reports');
    expect(header.textContent).not.toContain('03 [1]');
    expect(header.textContent).not.toContain('In work');
  });

  /** Newest first, which is the order the server sent and the view must not disturb. */
  it('keeps the versions of one document together and newest first', async () => {
    await answerWith(PAYLOAD);

    const versions = drawnRows()
      .filter((text) => text.includes('N-A') && !text.includes('versions'))
      .map((text) => text.match(/0\d \[\d]/)?.[0]);

    expect(versions).toEqual(['03 [1]', '02 [2]', '01 [2]']);
  });

  /** Collapsing hides a group's versions and leaves every other row alone. */
  it('collapses and expands a group from its header', async () => {
    await answerWith(PAYLOAD);
    expect(renderedText()).toContain('03 [1]');

    groupHeaders()[0].click();
    await settle();

    expect(renderedText()).not.toContain('03 [1]');
    // The header itself stays, and so does everything outside the group.
    expect(renderedText()).toContain('N-A');
    expect(renderedText()).toContain('02 [1]');

    groupHeaders()[0].click();
    await settle();

    expect(renderedText()).toContain('03 [1]');
  });

  /**
   * The **header itself** has to redraw, not only the rows under it.
   *
   * This is the assertion the first version of this spec was missing, and the browser is where the
   * gap showed: the versions vanished and the twisty kept pointing down, because ag-grid updates a
   * row in place and calls `refresh`, and a plain field written there never re-renders an OnPush
   * view in a zoneless application. `aria-expanded` is the same fact the arrow carries, and it is
   * the half a jsdom spec can see.
   */
  it('flips the header disclosure state when the group is collapsed', async () => {
    await answerWith(PAYLOAD);
    expect(groupHeaders()[0].getAttribute('aria-expanded')).toBe('true');

    groupHeaders()[0].click();
    await settle();

    expect(groupHeaders()[0].getAttribute('aria-expanded')).toBe('false');
    expect(groupHeaders()[0].querySelector('.sec-windchill-group__twisty--closed')).toBeTruthy();

    groupHeaders()[0].click();
    await settle();

    expect(groupHeaders()[0].getAttribute('aria-expanded')).toBe('true');
  });

  // -- searching ------------------------------------------------------------------------------

  /**
   * The search reads every column, and it costs no request.
   *
   * `httpTesting.verify()` in `afterEach` is the second half of that assertion: a request this
   * spec did not expect would fail there.
   */
  it('filters on any column without going back to the server', async () => {
    await answerWith(PAYLOAD);

    await search('unterauftrag');

    expect(renderedText()).toContain('Unterauftragnehmer');
    expect(renderedText()).not.toContain('Quartalsbericht');
    expect(renderedText()).toContain('2 of 6 documents');
  });

  it('searches the state column too, which is not the one a reader would guess', async () => {
    await answerWith(PAYLOAD);

    await search('in work');

    expect(renderedText()).toContain('Projektplan');
    expect(renderedText()).not.toContain('Unterauftragnehmer');
  });

  /**
   * A header says how many versions the **document** has, not how many the search left.
   *
   * Counting after the filter would make headers appear and disappear as a reader typed, which
   * reads as the data changing rather than as the view narrowing.
   */
  it('keeps a header, and its count, when the search matches only one version', async () => {
    await answerWith(PAYLOAD);

    await search('03 [1]');

    expect(groupHeaders().length).toBe(1);
    expect(groupHeaders()[0].textContent).toContain('3 versions');
  });

  it('says so when nothing matches, and keeps the term in the box', async () => {
    await answerWith(PAYLOAD);

    await search('nothing-matches-this');

    expect(renderedText()).toContain('No documents match');
    expect(searchBox().value).toBe('nothing-matches-this');
  });

  // -- the states around the table -------------------------------------------------------------

  it('invites an import when nothing has been imported yet', async () => {
    await answerWith(EMPTY);

    expect(renderedText()).toContain('No Windchill documents imported yet');
  });

  /** An absent host removes the link out and says why, rather than drawing a link that goes nowhere. */
  it('says when no Windchill address is configured', async () => {
    await answerWith({
      ...PAYLOAD,
      hostConfigured: false,
      rows: ROWS.map((document) => ({ ...document, browseUrl: null })),
    });

    expect(renderedText()).toContain('No Windchill address is configured');
    expect(fixture.nativeElement.querySelector('.sec-windchill-link')).toBeNull();
  });

  it('links each version to its own Windchill info page', async () => {
    await answerWith(PAYLOAD);

    const links = Array.from(
      fixture.nativeElement.querySelectorAll('.sec-windchill-link'),
    ) as HTMLAnchorElement[];

    // One per document and none on a header: a header is not a document and has nothing to open.
    expect(links.length).toBe(ROWS.length);
    expect(links[0].getAttribute('href')).toContain('infoPage?oid=OR:a3');
    expect(links[0].getAttribute('rel')).toBe('noopener noreferrer');
  });

  /** A cap hit silently is a table that is quietly wrong. */
  it('says when the server truncated the set', async () => {
    await answerWith({ ...PAYLOAD, truncated: true });

    expect(renderedText()).toContain('that limit was reached');
  });
});
