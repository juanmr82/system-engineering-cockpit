import { ApplicationRef, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { By } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { AuthStore } from '../../core/auth/auth-store';
import { DEFAULT_NAV_GROUPS } from '../../core/navigation/nav-group';
import { NavigationService } from '../../core/navigation/navigation.service';
import { SidenavCollapseService } from '../sidenav/sidenav-collapse.service';
import { Shell } from './shell';

class FakeAuthStore {
  hasRole = (): boolean => false;
  readonly user = signal(null);
}

class FakeNavigationService {
  readonly groups = signal(DEFAULT_NAV_GROUPS);
}

describe('Shell', () => {
  let fixture: ComponentFixture<Shell>;
  let appRef: ApplicationRef;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Shell],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: AuthStore, useValue: new FakeAuthStore() },
        { provide: NavigationService, useValue: new FakeNavigationService() },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Shell);
    appRef = TestBed.inject(ApplicationRef);
  });

  // Angular Material's MatDrawerContainer only re-measures mat-sidenav-content's margin on a
  // drawer open/close toggle, a mode change, an RTL flip, or a viewport resize — never merely
  // because an already-opened mode="side" drawer's own width changed (shell.scss has the full
  // explanation, including why an inline-style override was not the fix: it never un-toggled).
  // sec-shell--collapsed is what actually drives the margin now, in both directions, via CSS.
  it('toggles sec-shell--collapsed with the collapse signal, in both directions', async () => {
    fixture.detectChanges();
    await appRef.whenStable();
    fixture.detectChanges();

    const container = fixture.debugElement.query(By.css('mat-sidenav-container'))
      .nativeElement as HTMLElement;
    expect(container.classList.contains('sec-shell--collapsed')).toBe(false);

    TestBed.inject(SidenavCollapseService).toggle();
    fixture.detectChanges();
    expect(container.classList.contains('sec-shell--collapsed')).toBe(true);

    TestBed.inject(SidenavCollapseService).toggle();
    fixture.detectChanges();
    expect(container.classList.contains('sec-shell--collapsed')).toBe(false);
  });
});
