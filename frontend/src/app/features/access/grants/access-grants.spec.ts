import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { vi } from 'vitest';
import { AuthStore } from '../../../core/auth/auth-store';
import { settleGrid } from '../../../core/grid/grid-testing';
import type { AccessCategoryListResponse } from '../access.model';
import type { GroupListResponse } from '../access.model';
import { AccessGrants } from './access-grants';

const CATEGORIES: AccessCategoryListResponse = {
  categories: [
    { ref: 'Y2F0LTE', key: 'doors-srd', name: 'SRD', description: '', everyGroup: false, objectCount: 5, groupCount: 1 },
    { ref: 'Y2F0LTI', key: 'doors-icd', name: 'ICD', description: '', everyGroup: false, objectCount: 2, groupCount: 0 },
  ],
};

const GROUPS: GroupListResponse = {
  groups: [
    {
      ref: 'L1NFQy9UaGVybWFs',
      key: '/SEC/Thermal',
      name: '/SEC/Thermal',
      seesAll: false,
      categoryRefs: ['Y2F0LTE'],
      firstSeenAt: '2026-01-01T00:00:00Z',
      lastSeenAt: '2026-01-01T00:00:00Z',
    },
    {
      ref: 'L1NFQy9Bdmlvbmljcw',
      key: '/SEC/Avionics',
      name: '/SEC/Avionics',
      seesAll: false,
      categoryRefs: [],
      firstSeenAt: '2026-01-01T00:00:00Z',
      lastSeenAt: '2026-01-01T00:00:00Z',
    },
  ],
};

class FakeAuthStore {
  isLoading = (): boolean => false;
  hasRole = (): boolean => true;
}

describe('AccessGrants', () => {
  let fixture: ComponentFixture<AccessGrants>;
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

  const grantCheckbox = (categoryName: string, groupName: string): HTMLInputElement =>
    require<HTMLInputElement>(`input[aria-label="Grant ${categoryName} to ${groupName}"]`);

  const saveButtonFor = (groupName: string): HTMLButtonElement =>
    require<HTMLButtonElement>(`[aria-label="Save grants for ${groupName}"]`);

  const settle = async (): Promise<void> => {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  };

  const flushReload = async (url: string, response: GroupListResponse): Promise<void> => {
    // reload() schedules the refetch rather than issuing it — the same trap step 8's specs
    // found — so a detectChanges() plus a macrotask tick is what actually pulls the resource's
    // effect before the request exists to flush.
    fixture.detectChanges();
    await new Promise((resolve) => setTimeout(resolve, 0));
    httpTesting.expectOne(url).flush(response);
  };

  async function setUp(): Promise<void> {
    fakeAuthStore = new FakeAuthStore();
    await TestBed.configureTestingModule({
      imports: [AccessGrants],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideNoopAnimations(),
        { provide: AuthStore, useValue: fakeAuthStore },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AccessGrants);
    httpTesting = TestBed.inject(HttpTestingController);
    fixture.detectChanges();

    httpTesting.expectOne('/api/v1/access/groups').flush(GROUPS);
    httpTesting.expectOne('/api/v1/access/categories').flush(CATEGORIES);
    // AccessApiService also constructs unassignedContainers/defaults on injection; this view
    // reads neither, but every stray request still has to be flushed here or verify() sees it
    // hanging.
    httpTesting
      .match('/api/v1/access/containers?state=unassigned')
      .forEach((request) => request.flush({ containers: [] }));
    httpTesting.match('/api/v1/access/defaults').forEach((request) => request.flush({ defaults: [] }));
    await settleGrid(fixture);
  }

  afterEach(() => httpTesting.verify());

  it('refuses a caller without the Access manager role, without ever requesting groups or categories', async () => {
    fakeAuthStore = new FakeAuthStore();
    fakeAuthStore.hasRole = () => false;
    await TestBed.configureTestingModule({
      imports: [AccessGrants],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideNoopAnimations(),
        { provide: AuthStore, useValue: fakeAuthStore },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(AccessGrants);
    httpTesting = TestBed.inject(HttpTestingController);
    await settle();

    expect(renderedText()).toContain('Access manager role');
    httpTesting.expectNone('/api/v1/access/groups');
    httpTesting.expectNone('/api/v1/access/categories');
  });

  describe('for an access manager', () => {
    beforeEach(() => setUp());

    it('renders one column per category and checks exactly the grants each group holds', () => {
      expect(grantCheckbox('SRD', '/SEC/Thermal').checked).toBe(true);
      expect(grantCheckbox('ICD', '/SEC/Thermal').checked).toBe(false);
      expect(grantCheckbox('SRD', '/SEC/Avionics').checked).toBe(false);
      expect(grantCheckbox('ICD', '/SEC/Avionics').checked).toBe(false);
    });

    it('marks a row dirty on toggle and enables its own save button, leaving other rows untouched', async () => {
      expect(saveButtonFor('/SEC/Avionics').disabled).toBe(true);

      grantCheckbox('SRD', '/SEC/Avionics').click();
      await settle();

      expect(saveButtonFor('/SEC/Avionics').disabled).toBe(false);
      expect(saveButtonFor('/SEC/Thermal').disabled).toBe(true);
    });

    // The regression a per-row save renderer exists to prevent: toggling one checkbox and
    // saving posts exactly one PUT for exactly one group, every other row's dirty state
    // untouched.
    it('saves exactly one PUT for the row that was edited, and clears only that row', async () => {
      grantCheckbox('ICD', '/SEC/Thermal').click();
      await settle();

      saveButtonFor('/SEC/Thermal').click();

      const request = httpTesting.expectOne('/api/v1/access/groups/L1NFQy9UaGVybWFs/grants');
      expect(request.request.method).toBe('PUT');
      expect(request.request.body).toEqual({ categoryRefs: ['Y2F0LTE', 'Y2F0LTI'] });

      request.flush({ ...GROUPS.groups[0], categoryRefs: ['Y2F0LTE', 'Y2F0LTI'] });
      await settle();

      expect(saveButtonFor('/SEC/Thermal').disabled).toBe(true);
      expect(grantCheckbox('ICD', '/SEC/Thermal').checked).toBe(true);
      httpTesting.expectNone('/api/v1/access/groups/L1NFQy9Bdmlvbmljcw/grants');
    });

    it('un-checking back to the stored set clears the dirty mark without a save', async () => {
      const checkbox = grantCheckbox('SRD', '/SEC/Thermal');
      checkbox.click();
      await settle();
      expect(saveButtonFor('/SEC/Thermal').disabled).toBe(false);

      checkbox.click();
      await settle();
      expect(saveButtonFor('/SEC/Thermal').disabled).toBe(true);
    });

    it('opens a confirmation before granting "Sees everything", and does nothing on cancel', async () => {
      const openSpy = vi.spyOn(TestBed.inject(MatDialog), 'open').mockReturnValue({
        afterClosed: () => of(false),
      } as never);

      require<HTMLInputElement>('input[aria-label="Sees everything for /SEC/Thermal"]').click();
      await settle();

      const [, config] = openSpy.mock.calls.at(-1) as [unknown, { data: { message: string } }];
      expect(config.data.message).toContain('bypassing every category grant');
      httpTesting.expectNone('/api/v1/access/groups/L1NFQy9UaGVybWFs');
    });

    it('patches seesAll and reloads the group list when the confirmation is accepted', async () => {
      vi.spyOn(TestBed.inject(MatDialog), 'open').mockReturnValue({
        afterClosed: () => of(true),
      } as never);

      require<HTMLInputElement>('input[aria-label="Sees everything for /SEC/Thermal"]').click();
      await settle();

      const request = httpTesting.expectOne('/api/v1/access/groups/L1NFQy9UaGVybWFs');
      expect(request.request.method).toBe('PATCH');
      expect(request.request.body).toEqual({ seesAll: true });
      request.flush({ ...GROUPS.groups[0], seesAll: true });

      await flushReload('/api/v1/access/groups', { groups: [{ ...GROUPS.groups[0], seesAll: true }, GROUPS.groups[1]] });
      await settleGrid(fixture);

      expect(require<HTMLInputElement>('input[aria-label="Sees everything for /SEC/Thermal"]').checked).toBe(true);
    });
  });
});
