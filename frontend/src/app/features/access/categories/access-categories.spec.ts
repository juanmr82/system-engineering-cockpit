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
import { AccessCategories } from './access-categories';

const CATEGORIES: AccessCategoryListResponse = {
  categories: [
    {
      ref: 'Y2F0LTE',
      key: 'doors-srd',
      name: 'SRD',
      description: 'The SRD module',
      everyGroup: false,
      objectCount: 12,
      groupCount: 2,
    },
    {
      ref: 'Y2F0LTI',
      key: 'doors-icd',
      name: 'ICD',
      description: '',
      everyGroup: true,
      objectCount: 0,
      groupCount: 0,
    },
  ],
};

class FakeAuthStore {
  isLoading = (): boolean => false;
  hasRole = (): boolean => true;
}

describe('AccessCategories', () => {
  let fixture: ComponentFixture<AccessCategories>;
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

  const deleteButtonFor = (name: string): HTMLButtonElement =>
    require<HTMLButtonElement>(`[aria-label="Delete ${name}"]`);

  const settle = async (): Promise<void> => {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  };

  async function setUp(): Promise<void> {
    fakeAuthStore = new FakeAuthStore();
    await TestBed.configureTestingModule({
      imports: [AccessCategories],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideNoopAnimations(),
        { provide: AuthStore, useValue: fakeAuthStore },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AccessCategories);
    httpTesting = TestBed.inject(HttpTestingController);
    fixture.detectChanges();

    httpTesting.expectOne('/api/v1/access/categories').flush(CATEGORIES);
    // AccessApiService is providedIn: 'root' and constructs its `groups` httpResource alongside
    // `categories` on injection; this view never reads it, but the stray request still has to be
    // flushed here or verify() sees it hanging (the same trap step 8's own handover entry names).
    httpTesting.match('/api/v1/access/groups').forEach((request) => request.flush({ groups: [] }));
    await settleGrid(fixture);
  }

  afterEach(() => httpTesting.verify());

  it('refuses a caller without the Access manager role, without ever requesting the list', async () => {
    fakeAuthStore = new FakeAuthStore();
    fakeAuthStore.hasRole = () => false;
    await TestBed.configureTestingModule({
      imports: [AccessCategories],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideNoopAnimations(),
        { provide: AuthStore, useValue: fakeAuthStore },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(AccessCategories);
    httpTesting = TestBed.inject(HttpTestingController);
    await settle();

    expect(renderedText()).toContain('Access manager role');
    httpTesting.expectNone('/api/v1/access/categories');
  });

  describe('for an access manager', () => {
    beforeEach(() => setUp());

    it('lists every category with its counts', () => {
      expect(renderedText()).toContain('SRD');
      expect(renderedText()).toContain('ICD');
      expect(renderedText()).toContain('doors-srd');
      expect(renderedText()).toContain('2 categories');
    });

    // The dialog itself is covered by category-dialog.spec.ts; what matters here is only what
    // this view does once it closes with a save — the same split requirement-review.spec.ts uses
    // for its own settings dialog.
    it('opens the create dialog and reloads the list when it closes with a save', async () => {
      const openSpy = vi.spyOn(TestBed.inject(MatDialog), 'open').mockReturnValue({
        afterClosed: () => of(true),
      } as never);

      require<HTMLButtonElement>('.sec-access-categories__bar button').click();
      expect(openSpy).toHaveBeenCalled();

      // reload() schedules the refetch rather than issuing it, so the request does not exist
      // yet — the same trap requirement-review.spec.ts names on its own settings-save reload.
      // Not settle()/whenStable(): the request is not pending yet either, so there is nothing
      // for stability to wait on, and detectChanges() plus a task-queue flush is what actually
      // pulls the resource's effect.
      fixture.detectChanges();
      await new Promise((resolve) => setTimeout(resolve, 0));

      httpTesting
        .expectOne('/api/v1/access/categories')
        .flush({ categories: [...CATEGORIES.categories, { ...CATEGORIES.categories[0], ref: 'aWNkMg', key: 'doors-icd2', name: 'ICD 2' }] });
      await settleGrid(fixture);

      expect(renderedText()).toContain('ICD 2');
    });

    it('does nothing to the list when the create dialog is cancelled', async () => {
      vi.spyOn(TestBed.inject(MatDialog), 'open').mockReturnValue({
        afterClosed: () => of(false),
      } as never);

      require<HTMLButtonElement>('.sec-access-categories__bar button').click();
      await settle();

      httpTesting.expectNone('/api/v1/access/categories');
    });

    it('deletes on confirm and reloads the list, pre-empting the confirm message with the real counts', async () => {
      const openSpy = vi.spyOn(TestBed.inject(MatDialog), 'open').mockReturnValue({
        afterClosed: () => of(true),
      } as never);

      deleteButtonFor('ICD').click();

      const [, confirmConfig] = openSpy.mock.calls.at(-1) as [unknown, { data: { message: string } }];
      // ICD carries zero objects and zero groups — the plain destructive-confirm message, not the
      // in-use warning.
      expect(confirmConfig.data.message).toBe('Delete "ICD"? This cannot be undone.');

      httpTesting.expectOne('/api/v1/access/categories/Y2F0LTI').flush(null, { status: 204, statusText: 'No Content' });
      // reload() schedules the refetch rather than issuing it (the same trap the create-dialog
      // test above names) — a macrotask flush is what actually pulls the resource's effect.
      await settle();
      await new Promise((resolve) => setTimeout(resolve, 0));

      httpTesting.expectOne('/api/v1/access/categories').flush({ categories: [CATEGORIES.categories[0]] });
      await settleGrid(fixture);

      expect(renderedText()).not.toContain('ICD');
    });

    it('warns about the real counts, from the list row, before a category still in use is deleted', () => {
      const openSpy = vi.spyOn(TestBed.inject(MatDialog), 'open').mockReturnValue({
        afterClosed: () => of(false),
      } as never);

      deleteButtonFor('SRD').click();

      const [, confirmConfig] = openSpy.mock.calls.at(-1) as [unknown, { data: { message: string } }];
      expect(confirmConfig.data.message).toContain('granted to 2 group(s)');
      expect(confirmConfig.data.message).toContain('assigned to 12 object(s)');
    });

    // The defensive backstop (phase-6 plan §6.2): the delete is still attempted even though the
    // row already warned it would fail, and a real 409 surfaces inline rather than being lost.
    it('surfaces the 409 backstop inline when a still-in-use delete is attempted anyway', async () => {
      vi.spyOn(TestBed.inject(MatDialog), 'open').mockReturnValue({
        afterClosed: () => of(true),
      } as never);

      deleteButtonFor('SRD').click();

      httpTesting.expectOne('/api/v1/access/categories/Y2F0LTE').flush(
        {
          type: 'about:blank',
          title: 'Category still in use',
          status: 409,
          detail: 'This category is still granted to 2 group(s) and assigned to 12 object(s).',
        },
        { status: 409, statusText: 'Conflict' },
      );
      await settle();

      expect(renderedText()).toContain('This category is still granted to 2 group(s)');
    });
  });
});
