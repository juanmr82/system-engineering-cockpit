import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { flushGridFrames } from '../../../core/grid/grid-testing';
import { Modules, compareSystemLevels } from './modules';
import type { ModuleListResponse } from './modules.model';

// Three modules, deliberately out of level order and with one level unset, so the default sort
// has something to do. Two of the names differ only by an accent and by case — the pair that
// proves requirements-modules.md §3's "what the user sees is what gets searched" contract.
const RESPONSE: ModuleListResponse = {
  rows: [
    {
      ref: 'cmVmLTE',
      name: 'Systemanforderungen Höhenruder',
      lastModified: '30 July 2006',
      path: '/XXX-/Level 1 - System/SRD',
      wordExportTitle: 'Systemanforderungen Höhenruder',
      wordExportNumber: 'D-1234-56',
      systemLevel: { code: 'L1', label: 'L1 – System of Systems' },
    },
    {
      ref: 'cmVmLTI',
      name: 'Interface Control Document',
      lastModified: '04 November 2022',
      path: '/XXX-/Level 2 - Segment/ICD',
      // Never exported to Word: the module simply does not carry these, which is an absence and
      // not a fault, so the cells are empty rather than saying anything.
      wordExportTitle: '',
      wordExportNumber: '',
      systemLevel: null,
    },
    {
      ref: 'cmVmLTM',
      name: 'Customer requirements',
      lastModified: '12 January 2019',
      path: '/XXX-/Level 0 - Customer/CRD',
      wordExportTitle: 'Customer requirements document',
      wordExportNumber: 'D-0001-00',
      systemLevel: { code: 'L0', label: 'L0 – Customer' },
    },
    // A second L1, so the within-level order has something to preserve.
    {
      ref: 'cmVmLTQ',
      name: 'Thermal control',
      lastModified: '19 May 2024',
      path: '/XXX-/Level 1 - System/TCS',
      wordExportTitle: 'Thermal control system',
      wordExportNumber: 'D-2000-11',
      systemLevel: { code: 'L1', label: 'L1 – System of Systems' },
    },
  ],
};

const SYSTEM_LEVELS = [
  { code: 'L0', label: 'L0 – Customer' },
  { code: 'L1', label: 'L1 – System of Systems' },
  { code: 'L2', label: 'L2 – Segment' },
  { code: 'L3', label: 'L3 – Subsystem' },
  { code: 'L4', label: 'L4 – Component' },
];

describe('Modules', () => {
  let fixture: ComponentFixture<Modules>;
  let component: Modules;
  let httpTesting: HttpTestingController;

  const renderedText = (): string => fixture.nativeElement.textContent;

  const require = <T extends HTMLElement>(selector: string): T => {
    const found = fixture.nativeElement.querySelector(selector) as T | null;
    if (!found) {
      throw new Error(`No element matched ${selector}`);
    }
    return found;
  };

  const settle = async (): Promise<void> => {
    fixture.detectChanges();
    await fixture.whenStable();
    // Angular being stable is not the grid having drawn — see flushGridFrames.
    await flushGridFrames();
    fixture.detectChanges();
  };

  const levelSelects = (): HTMLSelectElement[] =>
    Array.from(fixture.nativeElement.querySelectorAll('.sec-module-level-cell__select'));

  // The module names in the order the grid actually drew them.
  const renderedNames = (): string[] =>
    Array.from(
      fixture.nativeElement.querySelectorAll('.sec-module-name-cell__name'),
    ).map((cell) => (cell as HTMLElement).textContent?.trim() ?? '');

  // By module rather than by position: row order is the system level, not fixture order, and a
  // positional helper quietly tests the wrong row.
  const levelSelectFor = (moduleName: string): HTMLSelectElement =>
    require<HTMLSelectElement>(`[aria-label="System level for ${moduleName}"]`);

  const choose = async (select: HTMLSelectElement, code: string): Promise<void> => {
    select.value = code;
    select.dispatchEvent(new Event('change'));
    await settle();
  };

  /** Types into the search box and lets the 200ms debounce elapse. */
  const search = async (term: string): Promise<void> => {
    const input: HTMLInputElement = fixture.nativeElement.querySelector('input[type="text"]');
    input.value = term;
    input.dispatchEvent(new Event('input'));
    await new Promise((resolve) => setTimeout(resolve, 250));
    fixture.detectChanges();
    await fixture.whenStable();
    // Angular being stable is not the grid having drawn — see flushGridFrames.
    await flushGridFrames();
    fixture.detectChanges();
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Modules],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations()],
    }).compileComponents();

    fixture = TestBed.createComponent(Modules);
    component = fixture.componentInstance;
    httpTesting = TestBed.inject(HttpTestingController);
    fixture.detectChanges();

    httpTesting.expectOne('/api/v1/modules').flush(RESPONSE);

    // ModulesApiService is providedIn: 'root' and creates both of its httpResources on
    // construction, so injecting it for the table also kicks off the vocabulary request the
    // settings dialog needs. Answered here so verify() sees no stray request.
    // The real vocabulary: the System level cell is a select now, and its options — including the
    // wording — come from here, never from the client (R5).
    httpTesting
      .match('/api/v1/config/system-levels')
      .forEach((request) => request.flush({ levels: SYSTEM_LEVELS }));

    await fixture.whenStable();
    fixture.detectChanges();
    await flushGridFrames();
    fixture.detectChanges();
  });

  afterEach(() => httpTesting.verify());

  it('lists every imported module with its counts', () => {
    const text = renderedText();
    expect(text).toContain('Systemanforderungen Höhenruder');
    expect(text).toContain('Interface Control Document');
    expect(text).toContain('4 shown');
    expect(text).toContain('4 imported');
  });

  // The system level is Tier-2 data the application wrote, so the row shows the resolved label
  // from the alias map — never a code, and never a raw property name (R5).
  // The wording comes from the server's vocabulary and the stored code is never shown (R5). The
  // control carries the *selected* value, so the assertion is on the select, not on page text —
  // every option's label is in the DOM whatever is chosen.
  it('shows the resolved system level, and says so plainly when there is none', () => {
    const selects = levelSelects();
    expect(selects).toHaveLength(4);

    const chosen = selects.map((s) => s.options[s.selectedIndex].textContent?.trim());
    expect(chosen).toContain('L1 – System of Systems');
    expect(chosen).toContain('Not set');
    expect(renderedText()).not.toContain('__');
  });

  // R7: editing marks the row dirty and counts it on the control that saves it; choosing the
  // original value back is not an edit and must not be saved as one.
  it('counts pending levels, and stops counting one chosen back to its original', async () => {
    const withLevel = levelSelectFor('Systemanforderungen Höhenruder');

    await choose(withLevel, 'L3');
    expect(component.hasPendingLevels()).toBe(true);
    expect(renderedText()).toContain('1');

    await choose(withLevel, 'L1');
    expect(component.hasPendingLevels()).toBe(false);
  });

  // One gesture, one request, one transaction — and the response, not the request, is what clears
  // the dirty marks, so the table is never reloaded to find out what happened.
  it('saves every pending level in one request and clears the marks without reloading', async () => {
    await choose(levelSelectFor('Systemanforderungen Höhenruder'), 'L3');
    await choose(levelSelectFor('Interface Control Document'), 'L4');

    require<HTMLButtonElement>('.sec-modules__action--save').click();

    const request = httpTesting.expectOne('/api/v1/modules/system-levels');
    expect(request.request.body).toEqual({
      levels: [
        { ref: 'cmVmLTE', code: 'L3' },
        { ref: 'cmVmLTI', code: 'L4' },
      ],
    });

    request.flush({
      saved: [
        { ref: 'cmVmLTE', systemLevel: { code: 'L3', label: 'L3 – Subsystem' } },
        { ref: 'cmVmLTI', systemLevel: { code: 'L4', label: 'L4 – Component' } },
      ],
    });
    await settle();

    expect(component.hasPendingLevels()).toBe(false);
    // No reload: the list is not refetched, the overlay is what the table now shows.
    httpTesting.expectNone('/api/v1/modules');
  });

  // On failure nothing is written and every edit stays on screen, with the error inline.
  it('keeps the edits and shows the error when the save fails', async () => {
    await choose(levelSelectFor('Systemanforderungen Höhenruder'), 'L3');

    require<HTMLButtonElement>('.sec-modules__action--save').click();
    httpTesting
      .expectOne('/api/v1/modules/system-levels')
      .flush(
        { type: 'about:blank', title: 'Unknown module', status: 400, detail: 'Reload the list.' },
        { status: 400, statusText: 'Bad Request' },
      );
    await settle();

    expect(component.hasPendingLevels()).toBe(true);
    expect(renderedText()).toContain('Reload the list.');
  });

  it('searches case-insensitively', async () => {
    await search('interface');

    expect(renderedText()).toContain('Interface Control Document');
    expect(renderedText()).not.toContain('Systemanforderungen');
    expect(renderedText()).toContain('1 shown');
  });

  // DOORS module names carry umlauts, and a user typing on a keyboard without them must still
  // find the module.
  it('searches accent-insensitively', async () => {
    await search('hohenruder');

    expect(renderedText()).toContain('Systemanforderungen Höhenruder');
    expect(renderedText()).not.toContain('Interface Control Document');
  });

  it('says which term matched nothing rather than showing a bare table', async () => {
    await search('no-such-module');

    expect(renderedText()).toContain('No modules match "no-such-module"');
  });

  /**
   * The table opens in system-level order, L0 first — not alphabetically by name.
   *
   * That is the order the modules are read in, and it is why the level column exists. The fixture
   * arrives L1, unset, L0, L1, so a passing assertion cannot be the server's order coming through.
   *
   * The two L1 modules also pin the **within-level** order, which is deliberately *not* an explicit
   * second sort: a second sorted column makes ag-grid draw its multi-sort position badges in the
   * headers. `Array.prototype.sort` is stable and the server returns modules ordered by name, so
   * equal levels stay alphabetical on their own — this is the assertion that says so out loud, and
   * that would fail if either half of that stopped being true.
   */
  it('opens sorted by system level, lowest first, alphabetical within a level', () => {
    expect(renderedNames()).toEqual([
      'Customer requirements',
      'Systemanforderungen Höhenruder',
      'Thermal control',
      'Interface Control Document',
    ]);
  });

  /**
   * A module with no level sorts last, and **stays** last when the sort is reversed.
   *
   * Asserted on the comparator rather than by clicking the header: ag-grid multiplies a
   * comparator's result by -1 for a descending sort, so this is a rule about a sign, and driving
   * it through the grid's DOM would test the grid instead. A positive result means "a after b".
   */
  it('sorts a module with no level last in both directions', () => {
    expect(compareSystemLevels('L0 – Customer', 'L1 – System of Systems', false)).toBeLessThan(0);
    expect(compareSystemLevels('', 'L4 – Component', false)).toBeGreaterThan(0);
    // Descending: ag-grid will negate this, so a negative result is what keeps the unset row last.
    expect(compareSystemLevels('', 'L4 – Component', true)).toBeLessThan(0);
    expect(compareSystemLevels('L0 – Customer', '', true)).toBeGreaterThan(0);
  });

  /**
   * The gear comes before the name, so every row's icon is at the same x.
   *
   * Trailing the name, it landed wherever that row's text happened to end — a ragged column of
   * buttons that reads as a layout fault rather than as a control. Asserted on DOM order because
   * that is what produces the alignment; there is no width here to measure in jsdom.
   */
  it('puts the settings gear before the module name', () => {
    const cell = require<HTMLElement>('.sec-module-name-cell');
    const children = Array.from(cell.children).map((child) => child.tagName.toLowerCase());

    expect(children[0]).toBe('button');
    expect(cell.querySelector('.sec-module-name-cell__name')).toBe(cell.children[1]);
  });

  // Two module properties, not object attributes, so they are read by name rather than discovered.
  // A module never exported to Word carries neither and its cells stay empty (R5: an absence is
  // not a fault, and gets no wording of its own here).
  it('shows the Word export title and number, and leaves them empty when unset', () => {
    const text = renderedText();
    expect(text).toContain('Word export title');
    expect(text).toContain('Word export number');
    expect(text).toContain('D-1234-56');
    expect(text).toContain('Customer requirements document');
  });
});
