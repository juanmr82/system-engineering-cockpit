import { Component, computed, signal } from '@angular/core';
import { MatTooltipModule } from '@angular/material/tooltip';
import type { ICellRendererAngularComp } from 'ag-grid-angular';
import type { ICellRendererParams } from 'ag-grid-community';
import type { JiraIssueRow } from '../jira-issues.model';

/** How many list values are drawn before the rest become a count (spec §13.2). */
const MAX_CHIPS = 3;

/** Where a long string is cut, with the whole of it in the tooltip. */
const MAX_CHARS = 120;

/** What the renderer decided to draw. A closed set, so the template has no logic in it. */
interface ValueView {
  readonly kind: 'empty' | 'text' | 'list';
  readonly text: string;
  /** The full value, for the tooltip, when [text] is a truncation of it. */
  readonly full: string | null;
  readonly chips: readonly string[];
  /** How many list values are not drawn. Zero when they all are. */
  readonly overflow: number;
}

const EMPTY: ValueView = { kind: 'empty', text: '', full: null, chips: [], overflow: 0 };

/**
 * One configured column's value (spec §13.2).
 *
 * ## Why a renderer and not a formatter
 *
 * Three of the four shapes a JIRA value arrives in are not one string: a list renders as chips, a
 * long string renders truncated with the whole of it on hover, and an absent value renders as an
 * em-dash that must not be confused with the text "null". A `valueFormatter` returns a string and
 * would flatten all four into one.
 *
 * ## Null is not empty
 *
 * `null` from the server means the issue does not carry this field. It draws as an em-dash, which
 * says "nothing here" without claiming the field is blank — the same distinction DOORS's `""`
 * forces everywhere else in this application, arriving from the other direction.
 */
@Component({
  selector: 'sec-jira-value-cell',
  imports: [MatTooltipModule],
  templateUrl: './jira-value-cell.html',
  styleUrl: './jira-value-cell.scss',
})
export class JiraValueCell implements ICellRendererAngularComp {
  private readonly raw = signal<unknown>(null);

  protected readonly view = computed<ValueView>(() => describe(this.raw()));

  agInit(params: ICellRendererParams<JiraIssueRow>): void {
    this.raw.set(read(params));
  }

  // ag-grid reuses a renderer instance for a different row when it scrolls, so the value is
  // re-read rather than assumed to be the one this cell was created with.
  refresh(params: ICellRendererParams<JiraIssueRow>): boolean {
    this.raw.set(read(params));
    return true;
  }
}

/** The row's value for this column. `colId` is the field id — a `field` is never used (§6). */
function read(params: ICellRendererParams<JiraIssueRow>): unknown {
  const fieldId = params.colDef?.colId;
  if (!fieldId || !params.data) return null;

  return params.data.values[fieldId] ?? null;
}

/**
 * What to draw for one stored value.
 *
 * A pure function, so the decisions are testable without a grid: everything ag-grid contributes is
 * already gone by the time it is called.
 */
function describe(value: unknown): ValueView {
  if (value === null || value === undefined || value === '') return EMPTY;

  if (Array.isArray(value)) {
    const chips = value.map(asText).filter((entry) => entry.length > 0);
    if (chips.length === 0) return EMPTY;

    return {
      kind: 'list',
      text: '',
      full: chips.join(', '),
      chips: chips.slice(0, MAX_CHIPS),
      overflow: Math.max(0, chips.length - MAX_CHIPS),
    };
  }

  const text = asText(value);
  if (text.length === 0) return EMPTY;

  const truncated = text.length > MAX_CHARS;

  return {
    kind: 'text',
    text: truncated ? `${text.slice(0, MAX_CHARS)}…` : text,
    // Only when it is actually cut: a tooltip repeating what is already on screen is noise on
    // every cell of the table.
    full: truncated ? text : null,
    chips: [],
    overflow: 0,
  };
}

/**
 * A stored value as text.
 *
 * Booleans and numbers keep their type on the wire so a client can align a number, and this is
 * where that ends. An object cannot appear here — the importer flattens complex values to JSON
 * text and the projection derives a display string (§7.4) — so the fallback exists to be correct
 * rather than to be used.
 */
function asText(value: unknown): string {
  if (value === null || value === undefined) return '';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  return '';
}

export { describe as describeJiraValue, type ValueView as JiraValueView };
