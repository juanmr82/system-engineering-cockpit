import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { AuthStore } from '../../../core/auth/auth-store';
import { settleGrid } from '../../../core/grid/grid-testing';
import type { AccessCategoryListResponse, AccessDefaultsResponse } from '../access.model';
import { AccessDefaults } from './access-defaults';

const CATEGORIES: AccessCategoryListResponse = {
  categories: [
    { ref: 'Y2F0LTE', key: 'doors-srd', name: 'SRD', description: '', everyGroup: false, objectCount: 0, groupCount: 0 },
    { ref: 'Y2F0LTI', key: 'doors-icd', name: 'ICD', description: '', everyGroup: false, objectCount: 0, groupCount: 0 },
  ],
};

const DEFAULTS: AccessDefaultsResponse = {
  defaults: [
    { sourceId: 'doors', containerLabel: 'DOORSModule', categoryRef: null },
    { sourceId: 'jira', containerLabel: 'JiraProject', categoryRef: 'Y2F0LTE' },
    { sourceId: 'windchill', containerLabel: 'WindchillDocument', categoryRef: null },
  ],
};

class FakeAuthStore {
  isLoading = (): boolean => false;
  hasRole = (): boolean => true;
}

describe('AccessDefaults', () => {
  let fixture: ComponentFixture<AccessDefaults>;
  let httpTesting: HttpTestingController;
  let fakeAuthStore: FakeAuthStore;

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
    fixture.detectChanges();
  };

  const selectFor = (sourceId: string, containerLabel: string): HTMLSelectElement =>
    require<HTMLSelectElement>(`[aria-label="Default category for ${sourceId} ${containerLabel}"]`);

  const choose = async (select: HTMLSelectElement, value: string): Promise<void> => {
    select.value = value;
    select.dispatchEvent(new Event('change'));
    await settle();
  };

  const saveButton = (): HTMLButtonElement => require<HTMLButtonElement>('.sec-access-defaults__action');

  async function setUp(): Promise<void> {
    fakeAuthStore = new FakeAuthStore();
    await TestBed.configureTestingModule({
      imports: [AccessDefaults],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideNoopAnimations(),
        { provide: AuthStore, useValue: fakeAuthStore },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AccessDefaults);
    httpTesting = TestBed.inject(HttpTestingController);
    fixture.detectChanges();

    httpTesting.expectOne('/api/v1/access/defaults').flush(DEFAULTS);
    // AccessApiService constructs categories/groups/unassignedContainers alongside defaults on
    // injection (this view reads categories directly, for the select options, but not the
    // other two) — every stray request still has to be answered here for verify() not to see it
    // hanging (true for every screen since Categories; check again for the next resource added).
    httpTesting.expectOne('/api/v1/access/categories').flush(CATEGORIES);
    httpTesting.match('/api/v1/access/groups').forEach((request) => request.flush({ groups: [] }));
    httpTesting
      .match('/api/v1/access/containers?state=unassigned')
      .forEach((request) => request.flush({ containers: [] }));
    httpTesting
      .match('/api/v1/access/containers?state=all')
      .forEach((request) => request.flush({ containers: [] }));
    await settleGrid(fixture);
  }

  afterEach(() => httpTesting.verify());

  it('refuses a caller without the Access manager role, without ever requesting defaults', async () => {
    fakeAuthStore = new FakeAuthStore();
    fakeAuthStore.hasRole = () => false;
    await TestBed.configureTestingModule({
      imports: [AccessDefaults],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideNoopAnimations(),
        { provide: AuthStore, useValue: fakeAuthStore },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(AccessDefaults);
    httpTesting = TestBed.inject(HttpTestingController);
    await settle();

    expect(renderedText()).toContain('Access manager role');
    httpTesting.expectNone('/api/v1/access/defaults');
  });

  describe('for an access manager', () => {
    beforeEach(() => setUp());

    // "Empty is the default answer" (spec §10.2) — every known (sourceId, containerLabel) pair
    // is a row, whether or not a :__AccessDefault node exists for it yet.
    it('lists every known source-container pair, empty rendering as "Not assigned"', () => {
      expect(renderedText()).toContain('doors');
      expect(renderedText()).toContain('DOORSModule');
      expect(renderedText()).toContain('jira');
      expect(renderedText()).toContain('windchill');
      expect(selectFor('doors', 'DOORSModule').value).toBe('');
      expect(selectFor('jira', 'JiraProject').value).toBe('Y2F0LTE');
    });

    it('keeps Save disabled until a default actually changes', async () => {
      expect(saveButton().disabled).toBe(true);

      await choose(selectFor('doors', 'DOORSModule'), 'Y2F0LTE');

      expect(saveButton().disabled).toBe(false);
    });

    it('choosing back to the stored value clears the dirty mark without a save', async () => {
      await choose(selectFor('doors', 'DOORSModule'), 'Y2F0LTE');
      expect(saveButton().disabled).toBe(false);

      await choose(selectFor('doors', 'DOORSModule'), '');
      expect(saveButton().disabled).toBe(true);
    });

    it('saves the whole set in one PUT, every row not just the edited one', async () => {
      await choose(selectFor('doors', 'DOORSModule'), 'Y2F0LTE');

      saveButton().click();

      const request = httpTesting.expectOne('/api/v1/access/defaults');
      expect(request.request.method).toBe('PUT');
      expect(request.request.body).toEqual({
        defaults: [
          { sourceId: 'doors', containerLabel: 'DOORSModule', categoryRef: 'Y2F0LTE' },
          { sourceId: 'jira', containerLabel: 'JiraProject', categoryRef: 'Y2F0LTE' },
          { sourceId: 'windchill', containerLabel: 'WindchillDocument', categoryRef: null },
        ],
      });

      request.flush({
        defaults: [
          { sourceId: 'doors', containerLabel: 'DOORSModule', categoryRef: 'Y2F0LTE' },
          { sourceId: 'jira', containerLabel: 'JiraProject', categoryRef: 'Y2F0LTE' },
          { sourceId: 'windchill', containerLabel: 'WindchillDocument', categoryRef: null },
        ],
      });
      await settle();

      expect(saveButton().disabled).toBe(true);
      expect(selectFor('doors', 'DOORSModule').value).toBe('Y2F0LTE');
    });

    it('keeps the edit and shows the error inline when the save fails', async () => {
      await choose(selectFor('doors', 'DOORSModule'), 'Y2F0LTE');

      saveButton().click();
      httpTesting
        .expectOne('/api/v1/access/defaults')
        .flush(
          { type: 'about:blank', title: 'Unknown category', status: 400, detail: 'That category no longer exists.' },
          { status: 400, statusText: 'Bad Request' },
        );
      await settle();

      expect(saveButton().disabled).toBe(false);
      expect(renderedText()).toContain('That category no longer exists.');
    });
  });
});
