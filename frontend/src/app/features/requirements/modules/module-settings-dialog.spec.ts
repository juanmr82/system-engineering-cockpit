import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ModuleSettingsDialog } from './module-settings-dialog';
import type { ModuleAttributesResponse, ModuleDetail } from './modules.model';

const MODULE_REF = 'bW9kdWxlLTE';

const DETAIL: ModuleDetail = {
  ref: MODULE_REF,
  name: 'SRD',
  systemLevel: 'L2',
  // The label is the server's, from the alias map — `__version` reads as **Version**, never
  // Baseline, and never the property name itself (R5).
  properties: [
    { label: 'Version', value: 'Current' },
    { label: 'Word export title', value: 'The elevator SRD' },
  ],
};

// `visible` is set on one attribute and never shown by this dialog. It is the value the save test
// watches: the Modules dialog must post it back untouched rather than as false.
const ATTRIBUTES: ModuleAttributesResponse = {
  attributes: [
    { name: 'Object Text', mandatory: false, visible: true, verification: false, fixed: true },
    { name: 'REQ. Priorität', mandatory: true, visible: false, verification: false, fixed: false },
    { name: 'Verification Method', mandatory: false, visible: false, verification: true, fixed: false },
  ],
};

describe('ModuleSettingsDialog', () => {
  let fixture: ComponentFixture<ModuleSettingsDialog>;
  let httpTesting: HttpTestingController;
  let closed: boolean | undefined;

  const element = (): HTMLElement => fixture.nativeElement;
  const renderedText = (): string => element().textContent ?? '';

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

  const openAttributesTab = async (): Promise<void> => {
    const tab = Array.from(element().querySelectorAll<HTMLElement>('.mat-mdc-tab')).find((candidate) =>
      candidate.textContent?.includes('Object attributes'),
    );
    tab?.click();
    await settle();
  };

  const flagHeaders = (): string[] =>
    Array.from(element().querySelectorAll('.sec-attr-settings__flag-label')).map(
      (head) => head.textContent?.trim() ?? '',
    );

  beforeEach(async () => {
    closed = undefined;

    await TestBed.configureTestingModule({
      imports: [ModuleSettingsDialog],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideNoopAnimations(),
        { provide: MAT_DIALOG_DATA, useValue: { ref: MODULE_REF, name: 'SRD' } },
        { provide: MatDialogRef, useValue: { close: (result: boolean) => (closed = result) } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ModuleSettingsDialog);
    httpTesting = TestBed.inject(HttpTestingController);
    fixture.detectChanges();

    httpTesting.expectOne(`/api/v1/modules/${MODULE_REF}`).flush(DETAIL);
    httpTesting.expectOne(`/api/v1/modules/${MODULE_REF}/attributes`).flush(ATTRIBUTES);
    httpTesting.match('/api/v1/modules').forEach((request) => request.flush({ rows: [] }));
    httpTesting
      .match('/api/v1/config/system-levels')
      .forEach((request) => request.flush({ levels: [{ code: 'L2', label: 'L2 – Segment' }] }));

    await settle();
  });

  afterEach(() => httpTesting.verify());

  // Tab 1 is the module's own properties, rendered from the server's labels — the client never
  // maps a property name to wording of its own (R5).
  it('shows the module properties under the labels the server gave them', () => {
    expect(renderedText()).toContain('Version');
    expect(renderedText()).toContain('Current');
    expect(renderedText()).toContain('Word export title');
    expect(renderedText()).not.toContain('Baseline');
    expect(renderedText()).not.toContain('__');
  });

  /**
   * Tab 2 is the same searchable list the Req review settings dialog uses, minus one column.
   *
   * **Shown in table** configures the review table's columns and there is no table in this view,
   * so offering it here would be offering a setting whose effect is nowhere on screen.
   */
  it('lists the attributes with a search box, and without Shown in table', async () => {
    await openAttributesTab();

    expect(flagHeaders()).toEqual(['Mandatory', 'Verification attribute']);
    expect(require('.sec-attr-settings__search input')).toBeTruthy();
    expect(renderedText()).toContain('3 attributes');
    expect(renderedText()).toContain('REQ. Priorität');
  });

  it('searches attribute names case- and accent-insensitively', async () => {
    await openAttributesTab();

    const input = require<HTMLInputElement>('.sec-attr-settings__search input');
    input.value = 'prioritat';
    input.dispatchEvent(new Event('input'));
    await settle();

    expect(renderedText()).toContain('REQ. Priorität');
    expect(renderedText()).not.toContain('Verification Method');
    expect(renderedText()).toContain('1 of 3');
  });

  /**
   * The regression that matters most here.
   *
   * This dialog cannot show `visible`, and it posts the *absolute* state of every attribute — so
   * a row's `visible` has to travel back exactly as it arrived. Sending false for the flag this
   * dialog does not render would silently clear the review table's columns every time somebody
   * opened Module settings to change a system level.
   */
  it('posts the system level and every attribute, carrying the hidden flag back unchanged', async () => {
    await openAttributesTab();

    const checkboxes = Array.from(
      element().querySelectorAll<HTMLInputElement>('.sec-attr-settings__row input[type="checkbox"]'),
    );
    // Two flag columns per row, so the first row's Mandatory box is index 0.
    checkboxes[0].click();
    await settle();

    require<HTMLButtonElement>('mat-dialog-actions button:last-of-type').click();

    const request = httpTesting.expectOne(`/api/v1/modules/${MODULE_REF}/settings`);
    const body = request.request.body as {
      systemLevel: string | null;
      attributeSettings: { name: string; mandatory: boolean; visible: boolean; verification: boolean }[];
    };

    expect(body.systemLevel).toBe('L2');
    expect(body.attributeSettings).toEqual([
      { name: 'Object Text', mandatory: true, visible: true, verification: false },
      { name: 'REQ. Priorität', mandatory: true, visible: false, verification: false },
      { name: 'Verification Method', mandatory: false, visible: false, verification: true },
    ]);

    request.flush(DETAIL);
    await settle();
    expect(closed).toBe(true);
  });

  // R7: Save is disabled until the user changes something, and a failed write leaves the dialog
  // open with the input intact — there is no staging layer to recover it from.
  it('keeps the dialog open with the error inline when the save fails', async () => {
    const save = require<HTMLButtonElement>('mat-dialog-actions button:last-of-type');
    expect(save.disabled).toBe(true);

    await openAttributesTab();
    require<HTMLInputElement>('.sec-attr-settings__row input[type="checkbox"]').click();
    await settle();
    expect(save.disabled).toBe(false);

    save.click();
    httpTesting
      .expectOne(`/api/v1/modules/${MODULE_REF}/settings`)
      .flush(
        { type: 'about:blank', title: 'Conflict', status: 409, detail: 'Someone else changed this.' },
        { status: 409, statusText: 'Conflict' },
      );
    await settle();

    expect(closed).toBeUndefined();
    expect(renderedText()).toContain('Someone else changed this.');
  });
});
