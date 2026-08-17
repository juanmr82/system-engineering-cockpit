import { ComponentFixture, TestBed } from '@angular/core/testing';
import { RefusalPanel } from './refusal-panel';

describe('RefusalPanel', () => {
  let fixture: ComponentFixture<RefusalPanel>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [RefusalPanel] }).compileComponents();
    fixture = TestBed.createComponent(RefusalPanel);
  });

  it('names the required capability by default', () => {
    fixture.componentRef.setInput('title', 'Categories');
    fixture.detectChanges();

    const text: string = fixture.nativeElement.textContent;
    expect(text).toContain('Categories');
    expect(text).toContain('Access manager role');
  });

  it('accepts a caller-supplied description in place of the default', () => {
    fixture.componentRef.setInput('title', 'Grants');
    fixture.componentRef.setInput('description', 'A custom refusal sentence.');
    fixture.detectChanges();

    expect((fixture.nativeElement.textContent as string)).toContain('A custom refusal sentence.');
  });
});
