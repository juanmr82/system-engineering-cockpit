import { describe, expect, it } from 'vitest';
import { refGroup } from './review-table.model';
import type { Reference } from './review.model';

/**
 * The References cell splits its targets three ways, and the split is not cosmetic: it decides
 * what the cell tells a reviewer to *do*.
 *
 * A never-imported target is counted, and the tooltip names the module to import. An object DOORS
 * deleted is listed by its id, and the tooltip sends the reviewer to DOORS — the object was
 * imported, so there is nothing here left to import, and the id is what they will search for
 * there to find the link and remove it.
 *
 * A deleted target is `resolved` and it is not a placeholder. That is the trap these tests exist
 * for: it arrives with `resolved: true`, so any split that keys off `resolved` alone puts it in
 * the navigable list, where it renders as a working link to a requirement that is not there.
 */
const reference = (over: Partial<Reference> = {}): Reference => ({
  ref: 'cmVm',
  id: 'SRD-147',
  resolved: true,
  deletedInSource: false,
  moduleRef: 'bW9k',
  moduleName: 'SRD',
  ...over,
});

describe('refGroup', () => {
  it('lists resolved targets and counts nothing', () => {
    const group = refGroup([reference(), reference({ ref: 'cmVmMg', id: 'SRD-148' })]);

    expect(group.resolved).toHaveLength(2);
    expect(group.deleted).toEqual([]);
    expect(group.unresolvedCount).toBe(0);
  });

  it('counts targets whose module has not been imported', () => {
    const group = refGroup([
      reference({ id: null, resolved: false, moduleName: 'ICD' }),
      reference({ ref: 'cmVmMg', id: null, resolved: false, moduleName: 'ICD' }),
    ]);

    expect(group.unresolvedCount).toBe(2);
    expect(group.deleted).toEqual([]);
    expect(group.unresolvedTooltip).toContain('Import ICD');
  });

  it('lists deleted targets by id rather than counting them', () => {
    const group = refGroup([reference({ id: 'SRD-367', deletedInSource: true })]);

    expect(group.unresolvedCount).toBe(0);
    expect(group.deleted.map((entry) => entry.id)).toEqual(['SRD-367']);
    expect(group.deletedTooltip).toContain('deleted in DOORS');
    expect(group.deletedTooltip).not.toContain('Import');
  });

  /**
   * The whole reason the split cannot key off `resolved`.
   *
   * A deleted target is resolved — the object was imported and has its id and its text — so it
   * would land in the navigable list and be drawn as a button to a requirement that is gone.
   */
  it('keeps a deleted target out of the navigable list even though it is resolved', () => {
    const group = refGroup([reference({ id: 'SRD-367', deletedInSource: true })]);

    expect(group.resolved).toEqual([]);
  });

  /** Where the reviewer is sent, which is the only thing they can act on. */
  it('sends the reviewer to DOORS rather than to an import', () => {
    const group = refGroup([reference({ id: 'SRD-367', deletedInSource: true })]);

    expect(group.deletedTooltip).toContain('removed in DOORS');
  });

  /** The case the reference exports actually produce: all three against one object. */
  it('keeps the three states apart in one cell', () => {
    const group = refGroup([
      reference({ id: 'SRD-147' }),
      reference({ ref: 'Z29uZQ', id: 'SRD-367', deletedInSource: true }),
      reference({ ref: 'cGVuZA', id: null, resolved: false, moduleName: 'ICD' }),
    ]);

    expect(group.resolved.map((entry) => entry.id)).toEqual(['SRD-147']);
    expect(group.deleted.map((entry) => entry.id)).toEqual(['SRD-367']);
    expect(group.unresolvedCount).toBe(1);
    // The count must not swallow the deleted one: 1, not 2, or the tooltip promises that
    // importing ICD reveals an object that no longer exists.
    expect(group.unresolvedTooltip).toContain('Import ICD to see it');
  });

  it('says so when the owning module is not imported either', () => {
    const group = refGroup([reference({ id: null, resolved: false, moduleName: null })]);

    expect(group.unresolvedTooltip).toContain('neither is the module');
  });
});
