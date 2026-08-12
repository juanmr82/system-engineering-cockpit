import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { JiraColumnsDialog } from './jira-columns-dialog';
import type { JiraColumn, JiraField } from './jira-columns.model';

/**
 * The catalogue, with the three shapes that matter.
 *
 * Two fields share the name *Classification*, which is the ambiguity case the server marks; one is
 * an array, which cannot be sorted by; the rest are ordinary scalars.
 */
const FIELDS: JiraField[] = [
  {
    fieldId: 'assignee',
    name: 'Assignee',
    custom: false,
    schemaType: 'user',
    schemaItems: null,
    ambiguousName: false,
  },
  {
    fieldId: 'customfield_1',
    name: 'Classification',
    custom: true,
    schemaType: 'option',
    schemaItems: null,
    ambiguousName: true,
  },
  {
    fieldId: 'customfield_2',
    name: 'Classification',
    custom: true,
    schemaType: 'option',
    schemaItems: null,
    ambiguousName: true,
  },
  {
    fieldId: 'labels',
    name: 'Labels',
    custom: false,
    schemaType: 'array',
    schemaItems: 'string',
    ambiguousName: false,
  },
  {
    fieldId: 'summary',
    name: 'Summary',
    custom: false,
    schemaType: 'string',
    schemaItems: null,
    ambiguousName: false,
  },
];

const column = (fieldId: string, name: string, stale = false): JiraColumn => ({
  fieldId,
  name,
  schemaType: stale ? null : 'string',
  sortable: !stale,
  stale,
});

const CHOSEN: JiraColumn[] = [column('summary', 'Summary'), column('assignee', 'Assignee')];
const DEFAULTS: JiraColumn[] = [column('summary', 'Summary'), column('status', 'Status')];

describe('JiraColumnsDialog', () => {
  let fixture: ComponentFixture<JiraColumnsDialog>;
  let httpTesting: HttpTestingController;
  let closed: boolean | undefined;

  const element = (): HTMLElement => fixture.nativeElement;
  const renderedText = (): string => element().textContent ?? '';

  const settle = async (): Promise<void> => {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  };

  /** The chosen pane's rows, in the order they are drawn — which is the order they will be saved. */
  const chosenNames = (): string[] =>
    Array.from(element().querySelectorAll('.sec-columns__chosen-row .sec-columns__name')).map(
      (node) => node.textContent?.trim() ?? '',
    );

  const checkboxFor = (name: string): HTMLInputElement => {
    const row = Array.from(element().querySelectorAll('.sec-columns__row')).find((candidate) =>
      candidate.textContent?.includes(name),
    );
    const input = row?.querySelector<HTMLInputElement>('input[type="checkbox"]');
    if (!input) throw new Error(`No checkbox for ${name}`);
    return input;
  };

  const answer = async (): Promise<void> => {
    httpTesting.expectOne('/api/v1/jira/fields').flush(FIELDS);
    httpTesting.expectOne('/api/v1/jira/columns').flush(CHOSEN);
    // The defaults resource is created with the others and is answered so nothing is left open.
    httpTesting.expectOne('/api/v1/jira/columns/defaults').flush(DEFAULTS);
    await settle();
  };

  beforeEach(async () => {
    closed = undefined;

    await TestBed.configureTestingModule({
      imports: [JiraColumnsDialog],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideNoopAnimations(),
        { provide: MatDialogRef, useValue: { close: (result?: boolean) => (closed = result) } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(JiraColumnsDialog);
    httpTesting = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  afterEach(() => {
    httpTesting.verify();
  });

  it('opens on the columns that are configured, in their order', async () => {
    await answer();

    expect(chosenNames()).toEqual(['Summary', 'Assignee']);
  });

  /**
   * The disambiguating id, and only where it disambiguates.
   *
   * Fifteen names cover thirty-three fields on the reference instance, so two rows called
   * *Classification* are the same row to a reader. The id is appended to those and to nothing else
   * — putting it on every row would make the common case unreadable to fix the rare one.
   */
  it('appends the field id only where the name is ambiguous', async () => {
    await answer();

    const ids = Array.from(element().querySelectorAll('.sec-columns__list .sec-columns__id')).map(
      (node) => node.textContent?.trim(),
    );

    expect(ids).toEqual(['customfield_1', 'customfield_2']);
  });

  /** Searching by id is how a user finds one of two fields with the same name. */
  it('searches by name and by field id', async () => {
    await answer();

    fixture.componentInstance['search'].set('customfield_2');
    await settle();

    const names = Array.from(element().querySelectorAll('.sec-columns__list .sec-columns__name'));
    expect(names.length).toBe(1);
    expect(names[0].textContent?.trim()).toBe('Classification');
  });

  /**
   * A checkbox writes to the dialog's buffer and to nothing else (R7).
   *
   * This is the assertion that keeps "the dialog must not write on every checkbox click" true: the
   * only request in the whole interaction is the one Save makes.
   */
  it('does not write until Save, and then writes once', async () => {
    await answer();

    checkboxFor('Labels').click();
    await settle();

    expect(chosenNames()).toEqual(['Summary', 'Assignee', 'Labels']);
    // Nothing has been sent: httpTesting.verify() in afterEach would fail on a stray request, and
    // this says it positively rather than by absence.
    httpTesting.expectNone('/api/v1/jira/columns');

    const save = Array.from(element().querySelectorAll('button')).find(
      (button) => button.textContent?.trim() === 'Save',
    );
    save?.click();
    await settle();

    const request = httpTesting.expectOne(
      (candidate) => candidate.url === '/api/v1/jira/columns' && candidate.method === 'PUT',
    );
    expect(request.request.body).toEqual({ fieldIds: ['summary', 'assignee', 'labels'] });

    request.flush([...CHOSEN, column('labels', 'Labels')]);
    await settle();

    // The save reloads the shared resource, so every consumer sees the new set. `reload()`
    // schedules a refetch rather than issuing one, so this is asserted after a settle, not before.
    httpTesting.expectOne('/api/v1/jira/columns').flush([...CHOSEN, column('labels', 'Labels')]);
    await settle();

    expect(closed).toBe(true);
  });

  /** Unticking removes it from the chosen pane — the two panes are one buffer seen twice. */
  it('unticking a field removes it from the chosen list', async () => {
    await answer();

    checkboxFor('Assignee').click();
    await settle();

    expect(chosenNames()).toEqual(['Summary']);
  });

  it('cancels without writing anything', async () => {
    await answer();

    checkboxFor('Labels').click();
    await settle();

    const cancel = Array.from(element().querySelectorAll('button')).find(
      (button) => button.textContent?.trim() === 'Cancel',
    );
    cancel?.click();
    await settle();

    expect(closed).toBe(false);
  });

  /**
   * §13.4: a chosen field JIRA no longer has.
   *
   * It is listed apart, under its own heading, and it is still in the buffer — a column that
   * removed itself would look like a bug, and the user is the one who gets to drop it.
   */
  it('lists a stale column separately, and keeps it until it is removed', async () => {
    httpTesting.expectOne('/api/v1/jira/fields').flush(FIELDS);
    httpTesting
      .expectOne('/api/v1/jira/columns')
      .flush([column('summary', 'Summary'), column('customfield_999', 'customfield_999', true)]);
    httpTesting.expectOne('/api/v1/jira/columns/defaults').flush(DEFAULTS);
    await settle();

    expect(renderedText()).toContain('No longer in JIRA');
    expect(chosenNames()).toContain('customfield_999');

    const remove = element().querySelector<HTMLButtonElement>(
      '[aria-label="Remove customfield_999"]',
    );
    remove?.click();
    await settle();

    expect(chosenNames()).not.toContain('customfield_999');
  });

  /** The server's defaults, fetched — never a second copy of that list held in the browser. */
  it('resets to the defaults the server declares', async () => {
    await answer();

    const reset = Array.from(element().querySelectorAll('button')).find(
      (button) => button.textContent?.trim() === 'Reset to defaults',
    );
    reset?.click();
    await settle();

    // `Status` is not in the catalogue fixture, so it renders as its own id — which is exactly what
    // a default naming a field this instance has not imported should do.
    expect(chosenNames()).toEqual(['Summary', 'status']);
  });

  it('says so when there is no catalogue to choose from', async () => {
    httpTesting.expectOne('/api/v1/jira/fields').flush([]);
    httpTesting.expectOne('/api/v1/jira/columns').flush([]);
    httpTesting.expectOne('/api/v1/jira/columns/defaults').flush([]);
    await settle();

    expect(renderedText()).toContain('No JIRA fields have been imported yet');
  });
});
