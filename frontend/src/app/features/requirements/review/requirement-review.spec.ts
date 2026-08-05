import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { RequirementReview } from './requirement-review';
import type { ModuleObjectsResponse, ReviewRow, SaveCommentsResponse } from './review.model';

const MODULE_REF = 'bW9kdWxlLTE';

function row(overrides: Partial<ReviewRow> & Pick<ReviewRow, 'ref' | 'id' | 'name'>): ReviewRow {
  return {
    type: 'Requirement',
    labels: ['SEItem', 'DOORSObject', 'DOORSRequirement'],
    level: 1,
    requirementLike: true,
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
      name: 'Höhenruder shall deflect',
      attributes: { 'Object Text': 'The system shall do X', 'REQ. Priorität': '' },
    }),
    row({
      ref: 'b2JqLTI',
      id: 'SRD-2',
      name: 'Scope',
      type: 'Heading',
      labels: ['SEItem', 'DOORSObject', 'DOORSHeading'],
      requirementLike: false,
      attributes: { 'Object Text': 'Context, not a requirement', 'REQ. Priorität': '' },
      comment: { metaId: 'meta-1', text: 'Checked at review', updatedAt: '2026-08-05T10:00:00Z' },
    }),
  ],
  total: 2,
  truncated: false,
};

const ATTRIBUTES = {
  attributes: [
    { name: 'Object Text', mandatory: true, visible: true, verification: false, fixed: false },
    // Not marked visible, so it must not become a column — the settings dialog decides, not the
    // component (REQ_REVIEW.md §6).
    { name: 'REQ. Priorität', mandatory: false, visible: false, verification: false, fixed: false },
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
    harness.detectChanges();
  };

  const commentBoxes = (): HTMLInputElement[] =>
    Array.from(element().querySelectorAll('.sec-review__comment'));

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

  beforeEach(async () => {
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
    httpTesting.expectOne(`/api/v1/modules/${MODULE_REF}/attributes`).flush(ATTRIBUTES);
    // ModulesApiService is root-provided and creates its resources on injection: the selector's
    // module list and the vocabulary the settings dialog reads.
    httpTesting.match('/api/v1/modules').forEach((request) => request.flush({ rows: [] }));
    httpTesting
      .match('/api/v1/config/system-levels')
      .forEach((request) => request.flush({ levels: [] }));

    await settle();
  });

  afterEach(() => httpTesting.verify());

  // Criterion 2: the columns are the module's, discovered at runtime. Nothing about a DOORS
  // attribute name is hardcoded, and an attribute not marked visible is not a column.
  it('builds its columns from the module, and never from a hardcoded list', () => {
    const text = renderedText();
    expect(text).toContain('Object Text');
    expect(text).not.toContain('REQ. Priorität');
    expect(text).toContain('The system shall do X');
  });

  // R5: no internal name may reach the table, in a header or anywhere else.
  it('shows no internal names', () => {
    expect(renderedText()).not.toContain('__');
  });

  it('searches the loaded rows case- and accent-insensitively', async () => {
    await search('hohenruder');

    expect(renderedText()).toContain('SRD-1');
    expect(renderedText()).not.toContain('SRD-2');
    expect(renderedText()).toContain('1 shown');
    expect(renderedText()).toContain('2 in module');
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
