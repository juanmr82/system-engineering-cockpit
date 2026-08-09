import type { GraphScope } from './graph.model';

/**
 * The dependency graph's one endpoint (docs/REQ_BREAKDOWN_GRAPH_VIEW §3).
 *
 * A URL builder rather than an injectable, because the dialog owns its own `httpResource` — created
 * with the dialog and torn down with it, so a closed graph is not still holding a response.
 *
 * **The whole scope is in the URL**, which is what makes a graph shareable in a review: the seed is
 * the opaque route handle (R5), and depth, direction and level strategy are query parameters the
 * server validates against closed sets.
 */
export const GraphApi = {
  url(scope: GraphScope): string {
    const query = new URLSearchParams({
      depth: String(scope.depth),
      direction: scope.direction,
      levels: scope.levelStrategy,
    });
    return `/api/v1/items/${scope.seedRef}/graph?${query}`;
  },
} as const;
