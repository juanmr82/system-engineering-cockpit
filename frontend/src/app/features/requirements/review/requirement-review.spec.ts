import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { vi } from 'vitest';
import { RouterTestingHarness } from '@angular/router/testing';
import { flushGridFrames } from '../../../core/grid/grid-testing';
import { RequirementReview } from './requirement-review';
import type { ModuleTablesResponse } from '../../../shared/doors-table/doors-table.model';
import type { ModuleObjectsResponse, ReviewRow, SaveCommentsResponse } from './review.model';

const MODULE_REF = 'bW9kdWxlLTE';

function row(overrides: Partial<ReviewRow> & Pick<ReviewRow, 'ref' | 'id' | 'name'>): ReviewRow {
  return {
    objectNumber: '1',
    type: 'Requirement',
    labels: ['SEItem', 'DOORSObject', 'DOORSRequirement'],
    level: 1,
    requirementLike: true,
    issues: [],
    attributes: {},
    references: { outgoing: [], incoming: [], incomingComplete: false },
    comment: null,
    ...overrides,
  };
}

const OBJECTS: ModuleObjectsResponse = {
  rows: [
    row({
      ref: 'b2JqLTE',
      id: 'SRD-1',
      // `__name`, which the table no longer shows anywhere: Description is built from the
      // attributes instead (§5). Left deliberately distinctive so a test can prove it is gone.
      name: 'Höhenruder shall deflect',
      objectNumber: '1',
      attributes: {
        // A requirement's Description. Carries the umlaut, because it is what the search box now
        // has to match accent-insensitively.
        'Object Text': 'Das Höhenruder shall deflect',
        'REQ. Priorität': '',
        // A visible attribute whose name carries a dot, on purpose. See the dot test below.
        'SYS. Rationale': 'Crosswind landing case',
      },
      // Both kinds of finding in one list, as the server composes them: the fixed rule's sentence
      // first, then the names of mandatory attributes with no value.
      issues: ['Object Type shall not be TBD', 'Rationale', 'Verification Method'],
      // Every target unresolved — the shape 376 SRD objects actually have. A placeholder carries
      // no DOORS id, so this row's References cell has ids for nothing and must still speak.
      references: {
        outgoing: [
          { ref: 'cGxhY2Vob2xkZXItMQ', id: null, resolved: false, moduleRef: 'bW9kLTk', moduleName: 'ICD' },
          { ref: 'cGxhY2Vob2xkZXItMg', id: null, resolved: false, moduleRef: 'bW9kLTk', moduleName: 'ICD' },
        ],
        incoming: [],
        incomingComplete: false,
      },
    }),
    row({
      ref: 'b2JqLTI',
      id: 'SRD-2',
      name: 'Scope',
      objectNumber: '2.1',
      type: 'Heading',
      level: 2,
      labels: ['SEItem', 'DOORSObject', 'DOORSHeading'],
      requirementLike: false,
      attributes: {
        'Object Heading': 'Scope',
        'REQ. Priorität': '',
        'SYS. Rationale': '',
      },
      comment: { metaId: 'meta-1', text: 'Checked at review', updatedAt: '2026-08-05T10:00:00Z' },
    }),
    // The container object of an embedded table. It stays in the list — the table is drawn on
    // this row, in the Description column, where DOORS draws it (DOORS_TABLES.md §1).
    row({
      ref: 'dGFibGUtMQ',
      id: 'SRD-998',
      name: 'SRD-998',
      objectNumber: '2.2',
      type: 'Table',
      labels: ['SEItem', 'DOORSObject', 'DOORSTBD', 'DOORSTable'],
      requirementLike: false,
      attributes: {},
    }),
    // A fragment of that table. Filtered out: the table it belongs to is already drawn above, so
    // showing this as well would print the same cell twice (§5).
    row({
      ref: 'b2JqLTM',
      id: 'SRD-9',
      name: 'A table cell',
      objectNumber: '2.2.0-1',
      labels: ['SEItem', 'DOORSObject', 'DOORSTableCell'],
      requirementLike: false,
      attributes: { 'Object Text': 'Torque limit 40 Nm' },
    }),
  ],
  total: 4,
  truncated: false,
};

// The reconstructed table for SRD-998, as the server assembles it (DOORS_TABLES.md §4.2). One of
// its cells carries a value for a visible attribute column, which the outer column has to surface
// rather than drop (§6.3).
const TABLES: ModuleTablesResponse = {
  tables: [
    {
      ref: 'dGFibGUtMQ',
      objectNumber: '2.2',
      id: 'SRD-998',
      rowCount: 2,
      columnCount: 2,
      headerRowCount: 1,
      columnWeights: [1, 1],
      rows: [
        {
          rowNumber: 1,
          isHeader: true,
          present: true,
          ref: 'cm93LTE',
          id: 'SRD-1171',
          cells: [
            { columnNumber: 1, present: true, ref: 'Y2VsbC0x', id: 'SRD-1172', text: 'Parameter' },
            { columnNumber: 2, present: true, ref: 'Y2VsbC0y', id: 'SRD-1173', text: 'Value' },
          ],
        },
        {
          rowNumber: 2,
          isHeader: false,
          present: true,
          ref: 'cm93LTI',
          id: 'SRD-1181',
          cells: [
            { columnNumber: 1, present: true, ref: 'Y2VsbC0z', id: 'SRD-1182', text: 'Torque limit' },
            { columnNumber: 2, present: true, ref: 'Y2VsbC00', id: 'SRD-1183', text: '40 Nm' },
          ],
        },
      ],
      extraBands: [],
      anomalies: [],
    },
  ],
};

const NO_TABLES: ModuleTablesResponse = { tables: [] };

const ATTRIBUTES = {
  attributes: [
    // Fixed: the Description column is built out of these two, so offering them as optional
    // columns would let the same sentence appear twice. The server decides, not the component.
    { name: 'Object Text', mandatory: true, visible: true, verification: false, fixed: true },
    { name: 'Object Heading', mandatory: false, visible: false, verification: false, fixed: true },
    // Not marked visible, so it must not become a column — the settings dialog decides, not the
    // component (REQ_REVIEW.md §6).
    { name: 'REQ. Priorität', mandatory: false, visible: false, verification: false, fixed: false },
    { name: 'SYS. Rationale', mandatory: false, visible: true, verification: false, fixed: false },
    // The module has a mandatory policy, which is what makes the issues filter meaningful — a
    // module without one can only ever report zero, and that is not the same as "all clear".
    { name: 'Rationale', mandatory: true, visible: false, verification: false, fixed: false },
  ],
};

describe('RequirementReview', () => {
  let harness: RouterTestingHarness;
  let component: RequirementReview;
  let httpTesting: HttpTestingController;

  const element = (): HTMLElement => harness.routeNativeElement as HTMLElement;
  const renderedText = (): string => element().textContent ?? '';

  // Every query in this spec is for an element the template renders unconditionally, so a miss is
  // a broken template rather than a legitimately absent node — worth failing loudly on.
  const require = <T extends HTMLElement>(selector: string): T => {
    const found = element().querySelector<T>(selector);
    if (!found) {
      throw new Error(`No element matched ${selector}`);
    }
    return found;
  };

  const settle = async (): Promise<void> => {
    harness.detectChanges();
    await harness.fixture.whenStable();
    // Angular being stable is not the grid having drawn — see flushGridFrames.
    await flushGridFrames();
    harness.detectChanges();
  };

  const commentBoxes = (): HTMLInputElement[] =>
    Array.from(element().querySelectorAll('.sec-comment-cell__input'));

  const type = async (input: HTMLInputElement, text: string): Promise<void> => {
    input.value = text;
    input.dispatchEvent(new Event('input'));
    await settle();
  };

  const search = async (term: string): Promise<void> => {
    const input = require<HTMLInputElement>('.sec-review__search input');
    input.value = term;
    input.dispatchEvent(new Event('input'));
    await new Promise((resolve) => setTimeout(resolve, 300));
    await settle();
  };

  // Mounting is a function rather than only a beforeEach because the attribute payload is an
  // input to the view's behaviour, not a fixture detail: a module with no mandatory attribute
  // renders a different bar from one that has them, and `moduleRef` is seeded from the route
  // snapshot once, so a mounted component cannot be moved to a different module's data.
  /**
   * Answer the tables request.
   *
   * Deliberately no `whenStable()` in here. An outstanding HttpClient request is a pending task, so
   * awaiting stability with the tables request in flight never resolves — the hook times out
   * instead of failing usefully. Flush, let the effects run, and flush again in case the resource
   * re-issued.
   */
  const flushTables = async (tables: ModuleTablesResponse): Promise<void> => {
    for (let attempt = 0; attempt < 2; attempt++) {
      httpTesting
        .match((request) => request.url.endsWith('/tables'))
        .forEach((request) => request.flush(tables));
      harness.detectChanges();
      await flushGridFrames();
    }
  };

  const mount = async (attributes: typeof ATTRIBUTES, tables = TABLES): Promise<void> => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideNoopAnimations(),
        // The module comes from the URL, not from a store, so the view is shareable and survives
        // a reload (§2). Navigating for real is what puts it there.
        provideRouter([{ path: 'requirements/review', component: RequirementReview }]),
      ],
    });

    harness = await RouterTestingHarness.create();
    httpTesting = TestBed.inject(HttpTestingController);
    component = await harness.navigateByUrl(
      `/requirements/review?module=${MODULE_REF}`,
      RequirementReview,
    );

    httpTesting.expectOne(`/api/v1/modules/${MODULE_REF}/objects`).flush(OBJECTS);
    httpTesting.expectOne(`/api/v1/modules/${MODULE_REF}/attributes`).flush(attributes);
    // ModulesApiService is root-provided and creates its resources on injection: the selector's
    // module list and the vocabulary the settings dialog reads.
    httpTesting.match('/api/v1/modules').forEach((request) => request.flush({ rows: [] }));
    httpTesting
      .match('/api/v1/config/system-levels')
      .forEach((request) => request.flush({ levels: [] }));

    await flushTables(tables);
    await settle();
  };

  beforeEach(() => mount(ATTRIBUTES));

  afterEach(() => httpTesting.verify());

  // Criterion 2: the columns are the module's, discovered at runtime. Nothing about a DOORS
  // attribute name is hardcoded, and an attribute not marked visible is not a column.
  it('builds its columns from the module, and never from a hardcoded list', () => {
    const text = renderedText();
    expect(text).toContain('SYS. Rationale');
    expect(text).not.toContain('REQ. Priorität');
  });

  // §5: Description replaced Name. A requirement reads as its own statement, and `__name` — which
  // is what the column used to show — appears nowhere.
  it('shows a requirement as its Object Text, under a Description header', () => {
    const text = renderedText();
    expect(text).toContain('Description');
    expect(text).not.toContain('Name');
    expect(text).toContain('Das Höhenruder shall deflect');
  });

  // §5: a heading reads as its outline number and its heading text, the way it reads in DOORS and
  // in the Word export.
  it('shows a heading as its outline number and heading text', () => {
    expect(renderedText()).toContain('2.1 Scope');
  });

  // §5: the two attributes Description is built from are fixed, so they must not also appear as
  // their own columns — the same sentence twice, in the table whose problem was too many columns.
  it('does not offer the Description source attributes as columns of their own', () => {
    const text = renderedText();
    expect(text).not.toContain('Object Text');
    expect(text).not.toContain('Object Heading');
  });

  // §5: table structure is hidden for now. It is a view filter, not a data decision — the row is
  // still imported and still in the graph.
  // The rows and cells of a table are hidden because the table itself is drawn on its container's
  // row — showing them as well would print every cell twice (DOORS_TABLES.md §1, REQ_REVIEW.md §5).
  // The "n in module" readout still counts them: this is a view filter, not a data decision.
  it('hides the parts of a table, and counts only what it shows', () => {
    const text = renderedText();
    expect(text).not.toContain('SRD-9');
    expect(text).toContain('3 shown');
    expect(text).toContain('4 in module');
  });

  // DOORS draws a table inside the main text column at its full width, with the surrounding
  // columns continuing on either side (DOORS_TABLES.md §1). So does this.
  it('draws an embedded table in the Description column, with table semantics', () => {
    const table = require<HTMLElement>('sec-doors-table [role="table"]');

    expect(table.querySelectorAll('[role="columnheader"]').length).toBe(2);
    expect(table.textContent).toContain('Parameter');
    expect(table.textContent).toContain('Torque limit');
    expect(table.textContent).toContain('40 Nm');
  });

  /**
   * The ID and Type columns are blank for a table, as they are in DOORS.
   *
   * A table object carries an `Object Type` — usually TBD, because DOORS does not type the parts of
   * an embedded table — and printing it says nothing about the figure on the row. No cell id is on
   * screen either: the id lives on the tooltip, which is the only way to tell which object a cell
   * is when an import goes wrong.
   */
  it('leaves the ID and Type columns blank for a table, and prints no cell ids', () => {
    const text = renderedText();

    expect(text).not.toContain('SRD-998');
    expect(text).not.toContain('SRD-1172');
    // 'Table' is this row's type, and the fixture is the only place that word could come from.
    expect(text).not.toContain('Table');
  });

  // A table shows its cells' `Object Text` and nothing else — §6.3's outer display columns are not
  // implemented, so the attribute columns are empty on a table's row rather than carrying values
  // from inside it.
  it('draws nothing but cell text, and leaves the attribute columns empty', () => {
    const table = require<HTMLElement>('sec-doors-table');

    expect(table.textContent).toContain('Torque limit');
    expect(table.textContent).not.toContain('SYS. Rationale');
    expect(element().querySelectorAll('.sec-doors-table__cell--outer').length).toBe(0);
  });

  // An empty or failed tables response costs the tables and nothing else: the requirements stay on
  // screen, and the `DOORSTable` row falls back to its ordinary Description text rather than a hole.
  it('still lists the module when it has no tables', async () => {
    TestBed.resetTestingModule();
    await mount(ATTRIBUTES, NO_TABLES);

    expect(element().querySelector('sec-doors-table')).toBeNull();
    expect(renderedText()).toContain('SRD-1');
    expect(renderedText()).toContain('3 shown');
  });

  // ADR 0006, the ag-grid trap that costs nothing to hit and gives no sign it was hit: ag-grid
  // reads a dot in a column's `field` as a property path, so `field: 'SYS. Rationale'` looks for
  // row['SYS']['Rationale'], finds nothing, and renders an empty cell with no error. Roughly a
  // third of the reference module's attribute names carry a dot. Every column therefore uses a
  // synthetic colId and a valueGetter, and this test is what says so.
  it('renders an attribute whose name contains a dot', () => {
    const text = renderedText();
    expect(text).toContain('SYS. Rationale');
    expect(text).toContain('Crosswind landing case');
  });

  // §5.1: an unresolved target has no DOORS id, so a References cell that only listed ids would
  // say nothing at all about the very links a reviewer needs to chase. 376 SRD objects have
  // references and not one of them resolves, so this is the common case, not the edge.
  it('says how many references are not yet imported, even when none of them has an id', () => {
    const text = renderedText();
    expect(text).toContain('2 not yet imported');
    expect(text).not.toContain('__');
  });

  // attribute-policy-checks.md: the finding is named, not counted. "2 issues" would tell a
  // reviewer to go and look, which is the click this column exists to save.
  it('names the mandatory attributes an object has no value for', () => {
    const text = renderedText();
    expect(text).toContain('Issues');
    expect(text).toContain('Rationale');
    expect(text).toContain('Verification Method');
  });

  // §5.3: a fixed rule's finding reads as a sentence, beside the bare attribute names. The rule
  // itself is enforced server-side and covered in ReviewFeatureTest; what matters here is that the
  // column renders whatever the server composed rather than assuming attribute names.
  it('shows a fixed check finding as a sentence in the same list', () => {
    expect(renderedText()).toContain('Object Type shall not be TBD');
  });

  // The count is over everything loaded, not over the filtered view: filtering a finding out of
  // sight must never read as having fixed it.
  it('narrows to the objects with issues, and keeps counting all of them', async () => {
    const toggles = Array.from(
      element().querySelectorAll<HTMLInputElement>('.sec-review__filter input'),
    );
    // Second checkbox is "Objects with issues"; the first is "Requirements only".
    toggles[1].click();
    await settle();

    expect(renderedText()).toContain('SRD-1');
    expect(renderedText()).not.toContain('SRD-2');
    expect(renderedText()).toContain('1 shown');
  });

  // "Nothing is wrong" and "nothing is checked" look identical in a table, and the second is what
  // a module with no mandatory policy is in. Leaving it to be inferred from a missing filter is
  // how a reviewer concludes a module is clean when it has never been examined.

  // §5.3: the settings dialog writes the mandatory policy, and the verdict computed from it rides
  // on the *objects*, not on the attribute list. Reloading only the attributes left the Issues
  // column answering the previous policy until the browser was refreshed by hand — in both
  // directions: a newly mandatory attribute reported nothing, and an un-ticked one kept reporting.
  it('re-reads the rows as well as the columns when attribute settings are saved', async () => {
    // Counted through the rendered list rather than by searching the text: "SYS. Rationale" is a
    // visible *column* here, so a substring match would be satisfied by the header.
    const issueItems = (): number => element().querySelectorAll('.sec-issues-cell__item').length;
    expect(issueItems()).toBeGreaterThan(0);

    // The dialog itself is covered by review-settings-dialog.spec.ts; what matters here is only
    // what the view does once it closes with a save.
    const openSpy = vi
      .spyOn(TestBed.inject(MatDialog), 'open')
      .mockReturnValue({ afterClosed: () => of(true) } as never);
    require<HTMLButtonElement>('.sec-review__action').click();
    expect(openSpy).toHaveBeenCalled();

    // `reload()` schedules the refetch rather than issuing it, so the requests do not exist yet.
    // Not `settle()`: with two resources in flight `whenStable()` never resolves, and the test
    // times out instead of failing on the assertion.
    harness.detectChanges();
    await new Promise((resolve) => setTimeout(resolve, 0));

    // Both resources refetch. Either one missing is the bug. Answered as if the reviewer had just
    // un-ticked every mandatory attribute, which is the direction that used to keep reporting.
    httpTesting
      .expectOne(`/api/v1/modules/${MODULE_REF}/attributes`)
      .flush({ attributes: ATTRIBUTES.attributes.map((a) => ({ ...a, mandatory: false })) });
    httpTesting
      .expectOne(`/api/v1/modules/${MODULE_REF}/objects`)
      .flush({ ...OBJECTS, rows: OBJECTS.rows.map((r) => ({ ...r, issues: [] })) });
    await settle();

    // The finding is gone without a manual browser refresh, and the bar now says why there is
    // nothing to report rather than leaving it to be inferred from a missing control.
    expect(issueItems()).toBe(0);
    // The hint is about the configurable half only — the fixed checks still run, so it must not
    // claim nothing is being checked.
    expect(renderedText()).toContain('No mandatory attributes');
    // The issues filter stays: a fixed check runs whatever the configuration, so an empty result
    // is an honest "none found" rather than "none looked for".
    expect(element().querySelectorAll('.sec-review__filter').length).toBe(2);

    openSpy.mockRestore();
  });

  // R5: no internal name may reach the table, in a header or anywhere else.
  it('shows no internal names', () => {
    expect(renderedText()).not.toContain('__');
  });

  // The haystack is what the table shows, which is now the Description rather than `__name`
  // (§3, §5). DOORS text carries umlauts and a reviewer may be typing on a keyboard without them.
  it('searches the loaded rows case- and accent-insensitively', async () => {
    await search('hohenruder');

    expect(renderedText()).toContain('SRD-1');
    expect(renderedText()).not.toContain('SRD-2');
    expect(renderedText()).toContain('1 shown');
    expect(renderedText()).toContain('4 in module');
  });

  // §11 O4: a heading is context, not a requirement, and the filter is over loaded rows.
  it('filters to requirement-like objects on request', async () => {
    const toggle = require<HTMLInputElement>('.sec-review__filter input');
    toggle.click();
    await settle();

    expect(renderedText()).toContain('SRD-1');
    expect(renderedText()).not.toContain('SRD-2');
  });

  // §5.2: editing marks the row dirty and counts it; typing the original text back is not an edit,
  // so it must not be saved as one.
  it('counts dirty comments, and stops counting one typed back to its original', async () => {
    const [first, second] = commentBoxes();

    await type(first, 'Needs a rationale');
    expect(component.hasPendingComments()).toBe(true);

    await type(second, 'Changed');
    expect(renderedText()).toContain('2');

    await type(second, 'Checked at review');
    await type(first, '');
    expect(component.hasPendingComments()).toBe(false);
  });

  // Criterion 5 and §5.2: one click, one request carrying every dirty comment, and the table is
  // not reloaded afterwards — the response is what clears the dirty marks.
  it('saves every dirty comment in one request and clears the marks without reloading', async () => {
    const [first] = commentBoxes();
    await type(first, 'Needs a rationale');

    const saveButton = require<HTMLButtonElement>('.sec-review__action--save');
    saveButton.click();

    const request = httpTesting.expectOne(`/api/v1/modules/${MODULE_REF}/comments`);
    expect(request.request.body).toEqual({
      comments: [{ ref: 'b2JqLTE', text: 'Needs a rationale' }],
    });

    const response: SaveCommentsResponse = {
      saved: [
        {
          ref: 'b2JqLTE',
          comment: { metaId: 'meta-2', text: 'Needs a rationale', updatedAt: '2026-08-05T11:00:00Z' },
        },
      ],
    };
    request.flush(response);
    await settle();

    expect(component.hasPendingComments()).toBe(false);
    expect(commentBoxes()[0].value).toBe('Needs a rationale');
  });

  // §5.2: on failure nothing is written and every edit stays on screen, with the error inline.
  it('keeps the edits and shows the error when the save fails', async () => {
    const [first] = commentBoxes();
    await type(first, 'Needs a rationale');

    const saveButton = require<HTMLButtonElement>('.sec-review__action--save');
    saveButton.click();

    httpTesting
      .expectOne(`/api/v1/modules/${MODULE_REF}/comments`)
      .flush(
        { type: 'about:blank', title: 'Unknown object', status: 400, detail: 'Reload the module.' },
        { status: 400, statusText: 'Bad Request' },
      );
    await settle();

    expect(component.hasPendingComments()).toBe(true);
    expect(commentBoxes()[0].value).toBe('Needs a rationale');
    expect(renderedText()).toContain('Reload the module.');
  });
});
