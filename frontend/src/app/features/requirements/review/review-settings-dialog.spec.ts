import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import type { ModuleAttributesResponse } from '../modules/modules.model';
import { ReviewSettingsDialog } from './review-settings-dialog';

const MODULE_REF = 'bW9kdWxlLTE';

function attribute(name: string, overrides: Partial<{ mandatory: boolean; visible: boolean; verification: boolean }> = {}) {
  return { name, mandatory: false, visible: false, verification: false, fixed: false, ...overrides };
}

// Names shaped like the reference module: dots, umlauts, and a group sharing a common word — the
// case the search box exists for.
const ATTRIBUTES: ModuleAttributesResponse = {
  attributes: [
    attribute('Object Text'),
    attribute('REQ. Priorität'),
    attribute('Verification Method'),
    attribute('Verification Status CDR'),
    attribute('Absolute Number', { mandatory: true }),
  ],
};

describe('ReviewSettingsDialog', () => {
  let fixture: ComponentFixture<ReviewSettingsDialog>;
  let httpTesting: HttpTestingController;
  let closed: boolean | undefined;

  const element = (): HTMLElement => fixture.nativeElement;
  const renderedText = (): string => element().textContent ?? '';
  // The list is `shared/attribute-settings/`, so the selectors are its, not this dialog's — which
  // is the point: this spec asserts the dialog wires the shared list correctly and the shared
  // list's own behaviour is asserted through the two dialogs that use it.
  const rows = (): HTMLElement[] => Array.from(element().querySelectorAll('.sec-attr-settings__row'));

  const require = <T extends HTMLElement>(selector: string): T => {
    const found = element().querySelector<T>(selector);
    if (!found) {
      throw new Error(`No element matched ${selector}`);
    }
    return found;
  };

  const settle = async (): Promise<void> => {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  };

  const search = async (term: string): Promise<void> => {
    const input = require<HTMLInputElement>('.sec-attr-settings__search input');
    input.value = term;
    input.dispatchEvent(new Event('input'));
    await settle();
  };

  /** The "All" or "None" button of one flag column, addressed by the column's position. */
  const bulk = async (column: number, action: 'All' | 'None'): Promise<void> => {
    const heads = Array.from(element().querySelectorAll('.sec-attr-settings__bulk'));
    const buttons = Array.from(heads[column].querySelectorAll('button'));
    const button = buttons.find((candidate) => candidate.textContent?.trim() === action);
    button?.click();
    await settle();
  };

  beforeEach(async () => {
    closed = undefined;

    await TestBed.configureTestingModule({
      imports: [ReviewSettingsDialog],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideNoopAnimations(),
        { provide: MAT_DIALOG_DATA, useValue: { ref: MODULE_REF, name: 'SRD' } },
        { provide: MatDialogRef, useValue: { close: (result: boolean) => (closed = result) } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ReviewSettingsDialog);
    httpTesting = TestBed.inject(HttpTestingController);
    fixture.detectChanges();

    httpTesting.expectOne(`/api/v1/modules/${MODULE_REF}/attributes`).flush(ATTRIBUTES);
    httpTesting.match('/api/v1/modules').forEach((request) => request.flush({ rows: [] }));
    httpTesting.match('/api/v1/config/system-levels').forEach((request) => request.flush({ levels: [] }));

    await settle();
  });

  afterEach(() => httpTesting.verify());

  // §6: every discovered attribute, plus the view's own columns shown as always-on.
  it('lists the module attributes and the fixed columns together, under one header', () => {
    expect(rows().length).toBe(ATTRIBUTES.attributes.length + 5);
    expect(renderedText()).toContain('Object Text');
    expect(renderedText()).toContain('always shown');
    expect(renderedText()).toContain('5 attributes');

    // One header for the whole list — the two-table shape produced a second one mid-list.
    expect(element().querySelectorAll('.sec-attr-settings__head').length).toBe(1);
  });

  it('searches attribute names case- and accent-insensitively', async () => {
    await search('prioritat');

    expect(renderedText()).toContain('REQ. Priorität');
    expect(renderedText()).not.toContain('Object Text');
    expect(renderedText()).toContain('1 of 5');
  });

  // The fixed columns are not attributes, so a search that still showed them would read as a
  // broken filter.
  it('hides the fixed columns while searching', async () => {
    await search('verification');

    expect(renderedText()).not.toContain('always shown');
    expect(rows().length).toBe(2);
  });

  it('says which term matched nothing rather than showing an empty list', async () => {
    await search('no-such-attribute');

    expect(renderedText()).toContain('No attribute matches "no-such-attribute"');
  });

  // The whole point of the bulk actions: filter to a family of attributes, then set the column for
  // all of them. They must also mark the form dirty, or the change could not be saved.
  it('applies a column to the filtered rows only, and enables Save', async () => {
    const save = require<HTMLButtonElement>('mat-dialog-actions button:last-of-type');
    expect(save.disabled).toBe(true);

    await search('verification');
    await bulk(1, 'All'); // "Shown in table"
    await search('');

    expect(save.disabled).toBe(false);

    const visible = fixture.componentInstance['allRows']().filter((row) => row.visible).map((row) => row.name);
    expect(visible).toEqual(['Verification Method', 'Verification Status CDR']);
  });

  /**
   * The search filters the view, never the payload.
   *
   * This is the regression that matters: with a filter active, sending only the rows on screen
   * would silently unset every attribute the reviewer could not see at that moment.
   */
  it('saves the absolute state of every attribute, not just the filtered ones', async () => {
    await search('verification');
    await bulk(0, 'All'); // "Mandatory"

    require<HTMLButtonElement>('mat-dialog-actions button:last-of-type').click();

    const request = httpTesting.expectOne(`/api/v1/modules/${MODULE_REF}/settings`);
    const body = request.request.body as { attributeSettings: { name: string; mandatory: boolean }[] };

    expect(body.attributeSettings.length).toBe(5);
    expect(body.attributeSettings.filter((row) => row.mandatory).map((row) => row.name)).toEqual([
      'Verification Method',
      'Verification Status CDR',
      'Absolute Number',
    ]);
    // The dialog does not show system level, so it must not send one — an explicit null would
    // clear the module's classification (SystemLevelChange).
    expect('systemLevel' in body).toBe(false);

    request.flush({});
    await settle();
    expect(closed).toBe(true);
  });
});
