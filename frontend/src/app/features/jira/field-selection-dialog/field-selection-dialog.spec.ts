import {
  groupStateOf,
  initialSelection,
  selectablePathsOf,
  togglablePathsOf,
} from './field-selection-dialog';
import type { FieldGroup } from './field-selection-dialog';
import type { JiraFieldNode } from '../jira.model';

function node(partial: Partial<JiraFieldNode> & { path: string }): JiraFieldNode {
  return {
    label: partial.path,
    type: '',
    sample: '',
    selectable: true,
    hasValues: true,
    selected: false,
    fixed: false,
    children: [],
    ...partial,
  };
}

/**
 * The tri-state rule, as a function.
 *
 * Driving this through a rendered `mat-checkbox` would test Material's indeterminate binding
 * rather than the rule, and jsdom cannot show which of the three states is on screen anyway.
 */
describe('groupStateOf', () => {
  const group = (own: Partial<JiraFieldNode>, ...leaves: string[]): FieldGroup => ({
    node: node({ path: 'status', ...own }),
    leaves: leaves.map((path) => node({ path })),
  });

  it('is unchecked when nothing under the field is chosen', () => {
    const state = groupStateOf(group({ selectable: false }, 'status.name', 'status.id'), new Set());
    expect(state).toEqual({ checked: false, indeterminate: false });
  });

  it('is indeterminate when some of a field is chosen', () => {
    // Without this, a field with one of five sub-keys chosen looks identical to one with none.
    const state = groupStateOf(
      group({ selectable: false }, 'status.name', 'status.id'),
      new Set(['status.name']),
    );
    expect(state).toEqual({ checked: false, indeterminate: true });
  });

  it('is checked only when every selectable path under the field is chosen', () => {
    const state = groupStateOf(
      group({ selectable: false }, 'status.name', 'status.id'),
      new Set(['status.name', 'status.id']),
    );
    expect(state).toEqual({ checked: true, indeterminate: false });
  });

  it('counts a scalar field itself, not only its leaves', () => {
    // `summary` has no sub-keys and is a column in its own right.
    const scalar: FieldGroup = { node: node({ path: 'summary' }), leaves: [] };
    expect(selectablePathsOf(scalar)).toEqual(['summary']);
    expect(groupStateOf(scalar, new Set(['summary']))).toEqual({
      checked: true,
      indeterminate: false,
    });
  });

  it('ignores a field that has no scalar of its own', () => {
    // `status` alone is an object: there is nothing to put in a cell for it, so it is a heading in
    // the tree rather than a selectable column.
    const parent = group({ selectable: false }, 'status.name');
    expect(selectablePathsOf(parent)).toEqual(['status.name']);
  });
});

describe('togglablePathsOf', () => {
  it('drops the fixed paths, which are always shown anyway', () => {
    const group: FieldGroup = {
      node: node({ path: 'issuetype', selectable: false }),
      leaves: [node({ path: 'issuetype.name', fixed: true }), node({ path: 'issuetype.id' })],
    };
    expect(togglablePathsOf(group)).toEqual(['issuetype.id']);
  });

  it('drops a field JIRA defines whose sub-keys are not known yet', () => {
    // An object field that no imported issue has a value for: the catalogue knows it exists, and
    // nothing knows what its sub-keys will be called. Offering one would be a guess, and a wrong
    // guess is a column blank for ever.
    const group: FieldGroup = {
      node: node({ path: 'customfield_20002', selectable: false, hasValues: false }),
      leaves: [],
    };
    expect(togglablePathsOf(group)).toEqual([]);
  });

  it('keeps a scalar field that has no values yet', () => {
    // Its schema states the path exactly, so the column can be chosen before anybody fills it in.
    const group: FieldGroup = {
      node: node({ path: 'customfield_20001', selectable: true, hasValues: false }),
      leaves: [],
    };
    expect(togglablePathsOf(group)).toEqual(['customfield_20001']);
  });
});

describe('initialSelection', () => {
  it('takes what the server says is chosen', () => {
    const chosen = initialSelection([
      node({ path: 'status', selectable: false, children: [node({ path: 'status.name', selected: true })] }),
      node({ path: 'summary', selected: false }),
    ]);
    expect([...chosen]).toEqual(['status.name']);
  });

  it('leaves the fixed columns out', () => {
    // They are always shown, so storing them would put the decision in two places — the day the
    // fixed pair changes, stored rows would disagree with the code.
    const chosen = initialSelection([
      node({ path: 'key', selected: true, fixed: true }),
      node({
        path: 'issuetype',
        selectable: false,
        children: [node({ path: 'issuetype.name', selected: true, fixed: true })],
      }),
      node({ path: 'summary', selected: true }),
    ]);
    expect([...chosen]).toEqual(['summary']);
  });
});
