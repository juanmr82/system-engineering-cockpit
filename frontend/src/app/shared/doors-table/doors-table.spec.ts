import { ComponentFixture, TestBed } from '@angular/core/testing';
import { DoorsTable } from './doors-table';
import { tableEntries, trackList } from './doors-table.model';
import type { DoorsTableCell, DoorsTableRow, DoorsTableView } from './doors-table.model';

// docs/DOORS_TABLES.md §8, component half. The fixture is a hand-written view model, so this
// suite needs no backend — step 3 of the build order is deliberately shippable on its own.

function cell(columnNumber: number, text: string, present = true): DoorsTableCell {
  return {
    columnNumber,
    present,
    ref: present ? `ref-c${columnNumber}` : null,
    id: present ? `SRD-11${70 + columnNumber}` : null,
    text: present ? text : '',
  };
}

function row(rowNumber: number, cells: DoorsTableCell[], isHeader = false): DoorsTableRow {
  return {
    rowNumber,
    isHeader,
    present: true,
    ref: `ref-r${rowNumber}`,
    id: `SRD-117${rowNumber}`,
    cells,
  };
}

function view(overrides: Partial<DoorsTableView> = {}): DoorsTableView {
  return {
    ref: 'ref-table',
    objectNumber: '2.1.0-1',
    rowCount: 2,
    columnCount: 2,
    id: 'SRD-998',
    headerRowCount: 1,
    columnWeights: [1, 1],
    rows: [
      row(1, [cell(1, 'Parameter'), cell(2, 'Value')], true),
      row(2, [cell(1, 'Mass'), cell(2, '12 kg')]),
    ],
    extraBands: [],
    anomalies: [],
    ...overrides,
  };
}

describe('DoorsTable', () => {
  let fixture: ComponentFixture<DoorsTable>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [DoorsTable] }).compileComponents();
    fixture = TestBed.createComponent(DoorsTable);
  });

  function render(table: DoorsTableView): HTMLElement {
    fixture.componentRef.setInput('table', table);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('draws every cell with table semantics, because a grid of divs has none', () => {
    const element = render(view());

    expect(element.querySelector('[role="table"]')).not.toBeNull();
    expect(element.querySelectorAll('[role="row"]').length).toBe(2);
    expect(element.querySelectorAll('[role="columnheader"]').length).toBe(2);
    expect(element.querySelectorAll('[role="cell"]').length).toBe(2);
    expect(element.querySelector('[role="table"]')?.getAttribute('aria-rowcount')).toBe('2');
  });

  // The first row is the header row for a screen reader and nothing more: it carries no class and
  // no weight of its own, because a bolded row reads as a heading inside a document that already
  // has headings.
  it('tells a screen reader which row is the header without drawing it differently', () => {
    const element = render(view());

    const rows = element.querySelectorAll('[role="row"]');
    expect(rows[0].querySelectorAll('[role="columnheader"]').length).toBe(2);
    expect(rows[1].querySelectorAll('[role="cell"]').length).toBe(2);
    expect(rows[0].className).toBe(rows[1].className);
  });

  // "" from DOORS means the attribute exists and is empty. It renders as an empty cell — never as
  // the two characters, and never as a fallback to the object's name or id (§3.5).
  it('renders an empty Object Text as an empty cell, not as a fallback', () => {
    const element = render(
      view({
        rowCount: 1,
        rows: [row(1, [cell(1, ''), cell(2, 'Value')], true)],
      }),
    );

    const cells = element.querySelectorAll('[role="columnheader"]');
    expect(cells[0].textContent?.trim()).toBe('');
    expect(cells[0].textContent).not.toContain('SRD-');
  });

  it('keeps the line breaks in multi-line cell text', () => {
    const element = render(
      view({ rowCount: 1, rows: [row(1, [cell(1, 'first\nsecond'), cell(2, '')], true)] }),
    );

    expect(element.querySelectorAll('[role="columnheader"]')[0].textContent).toContain(
      'first\nsecond',
    );
  });

  // A cell shows its Object Text and nothing else. The DOORS id stays on `title`, which is the
  // only way to tell which object a cell is when an import goes wrong, and never on screen.
  it('never prints an object id, and keeps it on the tooltip', () => {
    const element = render(view());

    expect(element.textContent).not.toContain('SRD-');
    expect(element.querySelector('[role="columnheader"]')?.getAttribute('title')).toBe('SRD-1171');
  });

  it('draws a structural gap as an absent cell rather than shifting the row', () => {
    const element = render(
      view({
        rowCount: 1,
        columnCount: 3,
        columnWeights: [1, 1, 1],
        rows: [row(1, [cell(1, 'a'), cell(2, '', false), cell(3, 'c')], true)],
      }),
    );

    const cells = element.querySelectorAll('[role="columnheader"]');
    expect(cells.length).toBe(3);
    expect(cells[1].classList).toContain('sec-doors-table__cell--absent');
    expect(cells[2].textContent?.trim()).toBe('c');
  });

  it('says the findings in a sentence, and lists them only when asked', () => {
    const element = render(
      view({
        anomalies: [
          {
            kind: 'DUPLICATE_COLUMN_ORDINAL',
            severity: 'ERROR',
            message: 'Two cells of this row claim column 1.',
            ref: 'ref-c1',
            id: 'SRD-1172',
            objectNumber: '2.1.0-1.0-1.0-1',
          },
        ],
      }),
    );

    const button = element.querySelector('.sec-doors-table__warning');
    expect(button?.textContent).toContain('1 finding on this table, 1 of them serious');
    expect(element.querySelector('.sec-doors-table__anomalies')).toBeNull();

    (button as HTMLButtonElement).click();
    fixture.detectChanges();

    const list = element.querySelector('.sec-doors-table__anomalies');
    expect(list?.textContent).toContain('Two cells of this row claim column 1.');
    expect(list?.textContent).toContain('SRD-1172');
  });

  it('shows no findings affordance when there is nothing to report', () => {
    const element = render(view());

    expect(element.querySelector('.sec-doors-table__warning')).toBeNull();
  });

  // An empty table is an absence stated in words, never a blank area (R5).
  it('says so when a table has no cells', () => {
    const element = render(
      view({ rowCount: 0, columnCount: 0, headerRowCount: 0, columnWeights: [], rows: [] }),
    );

    expect(element.querySelector('[role="table"]')).toBeNull();
    expect(element.textContent).toContain('This table has no cells to show.');
  });

  it('draws an unexpected child as a full-width band in its document-order position', () => {
    const element = render(
      view({
        extraBands: [{ ref: 'ref-cap', after: 1, id: 'SRD-1999', text: 'Table 1: masses' }],
      }),
    );

    const rows = [...element.querySelectorAll('[role="row"]')];
    expect(rows.length).toBe(3);
    expect(rows[1].textContent).toContain('Table 1: masses');
  });

  it('never renders cell text as markup', () => {
    const element = render(
      view({ rowCount: 1, rows: [row(1, [cell(1, '<b>bold</b>'), cell(2, '')], true)] }),
    );

    const header = element.querySelector('[role="columnheader"]');
    expect(header?.querySelector('b')).toBeNull();
    expect(header?.textContent).toContain('<b>bold</b>');
  });
});

describe('doors-table.model', () => {
  it('builds a fraction track list, never a pixel width', () => {
    expect(trackList(view({ columnCount: 3, columnWeights: [1, 2.5, 6] }))).toBe(
      'minmax(0, 1fr) minmax(0, 2.5fr) minmax(0, 6fr)',
    );
  });

  // minmax(0, …) is half of what makes a grid shrink; the fallback must not quietly drop it.
  it('falls back to equal fractions when the weights do not match the column count', () => {
    expect(trackList(view({ columnCount: 2, columnWeights: [] }))).toBe(
      'minmax(0, 1fr) minmax(0, 1fr)',
    );
  });

  it('interleaves bands between the rows they follow, not after all of them', () => {
    const entries = tableEntries(
      view({
        extraBands: [
          { ref: 'ref-top', after: 0, id: null, text: 'before' },
          { ref: 'ref-mid', after: 1, id: null, text: 'between' },
        ],
      }),
    );

    expect(entries.map((entry) => entry.kind)).toEqual(['band', 'row', 'band', 'row']);
  });

});
