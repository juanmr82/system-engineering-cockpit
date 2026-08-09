import type { SystemLevelOption } from '../../features/requirements/modules/modules.model';

// The card payload, mirroring `api/dto/RequirementCardDtos.kt`. One requirement as every view that
// draws one shows it — the Breakdown tab as a row, the dependency graph as a node
// (docs/REQ_BREAKDOWN_GRAPH_VIEW §5.1).
//
// `ref` is always the opaque route handle (R5), never a raw internal id.

export interface RequirementAttribute {
  readonly name: string;
  readonly value: string;
}

/**
 * One requirement, as a card.
 *
 * `resolved` false means the target is a placeholder the importer created for an object no import
 * has reached: it carries no DOORS id and no description, and the card renders it as "Not yet
 * imported", names the owning module, and does not link it — exactly as the References column does.
 *
 * `deletedInSource` is a different fact and the two never coincide. The object was really
 * imported — it has its id, its statement and its level — and a later export of its own module no
 * longer contained it, because DOORS deleted it and kept the links pointing at it. So the card
 * renders in full, and adds that the requirement it is showing no longer exists.
 *
 * Both of those are states, not styling: what a reader has to be able to tell apart is whether an
 * import would help. For a placeholder it would. For a deleted object nothing here will, and the
 * fix is a link to remove in DOORS.
 */
export interface RequirementCardNode {
  readonly ref: string;
  readonly id: string | null;
  readonly level: SystemLevelOption | null;
  readonly description: string;
  readonly resolved: boolean;
  readonly deletedInSource: boolean;
  readonly moduleRef: string | null;
  readonly moduleName: string | null;
  readonly verificationAttributes: RequirementAttribute[];
}

/**
 * How a card is laid out. **Padding and clamping only — never which fields are shown**
 * (docs/REQ_BREAKDOWN_GRAPH_VIEW §5.1).
 *
 * That is the whole contract of the shared component: if the breakdown row gains a field the graph
 * node gains it too, with no second change, so the two can never drift into showing different
 * facts about the same requirement. A density that hid a field would quietly end that.
 *
 * - `row` — a line of the breakdown tree. Full width, text unclamped.
 * - `node` — a graph node. Fixed width (`CARD_WIDTH`), description clamped to a few lines so one
 *   long requirement cannot make its band taller than the screen.
 */
export type RequirementCardDensity = 'row' | 'node';
