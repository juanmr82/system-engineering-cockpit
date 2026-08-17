import { TestBed } from '@angular/core/testing';
import { SidenavCollapseService } from './sidenav-collapse.service';

describe('SidenavCollapseService', () => {
  let service: SidenavCollapseService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(SidenavCollapseService);
  });

  // frontend/CLAUDE.md §9: "By default left nav-bar is expanded" and the state is never persisted.
  it('starts expanded', () => {
    expect(service.collapsed()).toBe(false);
  });

  it('flips on each toggle', () => {
    service.toggle();
    expect(service.collapsed()).toBe(true);

    service.toggle();
    expect(service.collapsed()).toBe(false);
  });
});
