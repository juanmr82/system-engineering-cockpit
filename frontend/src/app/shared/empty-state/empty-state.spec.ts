import { ComponentFixture, TestBed } from '@angular/core/testing';
import { EmptyState } from './empty-state';

describe('EmptyState', () => {
  let fixture: ComponentFixture<EmptyState>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [EmptyState] }).compileComponents();
    fixture = TestBed.createComponent(EmptyState);
  });

  it('names what will live here', () => {
    fixture.componentRef.setInput('title', 'Statistics');
    fixture.componentRef.setInput('description', 'Coverage metrics will live here.');
    fixture.detectChanges();

    const text: string = fixture.nativeElement.textContent;
    expect(text).toContain('Statistics');
    expect(text).toContain('Coverage metrics will live here.');
  });

  // An empty state with nothing to say is still not an apology (CLAUDE.md §9) — it renders the
  // title alone rather than an empty paragraph.
  it('omits the description paragraph when there is none', () => {
    fixture.componentRef.setInput('title', 'Functions');
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('p')).toBeNull();
  });
});
