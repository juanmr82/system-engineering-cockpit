/**
 * The wire types of the column picker (`api/dto/JiraDtos.kt`, spec §13.3, §13.4).
 *
 * A *field* is something JIRA defines and the picker may offer; a *column* is a field a user chose,
 * in a position. They are separate types because the second can name something the first no longer
 * contains — which is the whole of §13.4.
 */

import type { JiraColumn } from '../issues/jira-issues.model';

export type { JiraColumn };

/** One offerable field. Fields with no schema never appear — they cannot be rendered as a column. */
export interface JiraField {
  readonly fieldId: string;
  readonly name: string;
  /** `true` for `customfield_*`. Drives the System / Custom filter and nothing else. */
  readonly custom: boolean;
  readonly schemaType: string | null;
  /** The element type of an `array` field — `string`, `option`, `user`. */
  readonly schemaItems: string | null;
  /**
   * Another field carries this exact name.
   *
   * Computed by the server, because only the server sees the whole catalogue at once: a dialog
   * rendering one row at a time cannot tell that another row shares its name. Fifteen names cover
   * thirty-three fields on the reference instance, so the picker appends the id to these.
   */
  readonly ambiguousName: boolean;
}
