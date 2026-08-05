import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { Modules } from './modules';
import type { ModuleListResponse } from './modules.model';

// Two modules whose names differ only by an accent and by case — the pair that proves
// requirements-modules.md §3's "what the user sees is what gets searched" contract.
const RESPONSE: ModuleListResponse = {
  rows: [
    {
      ref: 'cmVmLTE',
      name: 'Systemanforderungen Höhenruder',
      lastModified: '30 July 2006',
      path: '/XXX-/Level 1 - System/SRD',
      systemLevel: { code: 'L1', label: 'L1 – System of Systems' },
    },
    {
      ref: 'cmVmLTI',
      name: 'Interface Control Document',
      lastModified: '04 November 2022',
      path: '/XXX-/Level 2 - Segment/ICD',
      systemLevel: null,
    },
  ],
};

describe('Modules', () => {
  let fixture: ComponentFixture<Modules>;
  let httpTesting: HttpTestingController;

  const renderedText = (): string => fixture.nativeElement.textContent;

  /** Types into the search box and lets the 200ms debounce elapse. */
  const search = async (term: string): Promise<void> => {
    const input: HTMLInputElement = fixture.nativeElement.querySelector('input[type="text"]');
    input.value = term;
    input.dispatchEvent(new Event('input'));
    await new Promise((resolve) => setTimeout(resolve, 250));
    fixture.detectChanges();
    await fixture.whenStable();
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Modules],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations()],
    }).compileComponents();

    fixture = TestBed.createComponent(Modules);
    httpTesting = TestBed.inject(HttpTestingController);
    fixture.detectChanges();

    httpTesting.expectOne('/api/v1/modules').flush(RESPONSE);

    // ModulesApiService is providedIn: 'root' and creates both of its httpResources on
    // construction, so injecting it for the table also kicks off the vocabulary request the
    // settings dialog needs. Answered here so verify() sees no stray request.
    httpTesting
      .match('/api/v1/config/system-levels')
      .forEach((request) => request.flush({ levels: [] }));

    await fixture.whenStable();
    fixture.detectChanges();
  });

  afterEach(() => httpTesting.verify());

  it('lists every imported module with its counts', () => {
    const text = renderedText();
    expect(text).toContain('Systemanforderungen Höhenruder');
    expect(text).toContain('Interface Control Document');
    expect(text).toContain('2 shown');
    expect(text).toContain('2 imported');
  });

  // The system level is Tier-2 data the application wrote, so the row shows the resolved label
  // from the alias map — never a code, and never a raw property name (R5).
  it('shows the resolved system level, and says so plainly when there is none', () => {
    const text = renderedText();
    expect(text).toContain('L1 – System of Systems');
    expect(text).toContain('Not set');
    expect(text).not.toContain('__');
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
});
