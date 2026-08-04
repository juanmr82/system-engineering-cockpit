# Ad-hoc Cypher API design

> Status: not yet authored. `CLAUDE.md` §5 requires the ad-hoc Cypher endpoint
> (`POST /api/v1/cypher/explain`, `POST /api/v1/cypher/run`) to implement "exactly the four
> layers" specified here, and explicitly forbids simplifying it or building it before the read
> API works. Write this document, then implement `com.sec.security.CypherGuard` against it.

## What belongs here

The four layers, in the order they must be enforced:

1. **Read access mode** — the session/transaction this endpoint ever opens is read-only,
   regardless of what the submitted query claims to do.
2. **Static validation** — reject queries that could write, that call unsafe procedures, or
   that fall outside whatever subset of Cypher is deemed safe to expose. `CLAUDE.md` is explicit
   that this must tokenize the query, not substring-match it; the exact tokenizer/allowlist
   approach belongs here.
3. **`EXPLAIN` plan inspection** — run the query through `EXPLAIN` first and inspect the plan
   (operators used, estimated rows) before ever executing it for real.
4. **Resource limits** — row limits, timeouts, and how they're enforced given Neo4j Community
   has no query governor (`CLAUDE.md` §7).

Also belongs here: the response shape for `/explain` vs. `/run`, error format for a rejected
query, and how this endpoint's one exception to R5 (real `__`-prefixed names shown to the user)
is documented in the UI itself.

## What does not belong here

Everything about the *rest* of the API (`/items`, `/tree`, `/modules`, annotations) is already
described in `CLAUDE.md` §5 "API shape" — this document is scoped to the ad-hoc console only.
