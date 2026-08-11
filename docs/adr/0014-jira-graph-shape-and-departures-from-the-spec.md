# ADR 0014: The JIRA feature's graph shape, and every place it departs from its own spec

Status: accepted
Date: 2026-08-11

## Context

`docs/JIRA_ISSUES_FEATURE_SPEC.md` is a 1 135-line specification written before any of it was
built. Building steps 1–6 of its own build order turned up eleven points where following it literally
would have produced something wrong, inconsistent with the rest of this repository, or — in two
cases — impossible.

None of them is a disagreement with the spec's *intent*. Every one is a place where the spec was
written against a reasonable assumption that the code, the repository, or a real JIRA instance
then contradicted. This ADR records all eleven in one place, because the alternative is eleven comments
scattered across the source that each look like an oversight to the next reader.

CLAUDE.md's own conflict rule applies throughout: *the spec wins for its own subject area (importer
mechanics, graph schema), and CLAUDE.md wins for everything else.* Where that rule settles a point,
it is cited rather than re-argued.

## Decision

### 1. `:__UNDEFINED`, not the spec's new `:__UNRESOLVED`

Spec §6.1 introduces `:__UNRESOLVED` for a link target no import has reached yet. That is the
concept `:__UNDEFINED` already names — it is declared in `domain/GraphNames.kt`, written by the
DOORS importer, and has agreed R5 wording (*Not yet imported*) with the owning module named.

Two labels for one idea would mean two placeholder states, two empty-state sentences, and a tree
component that has to know which source it is looking at to say the same thing. **Reuse the
existing one.** This is the R3 case exactly: a new source joins the vocabulary, it does not extend
it.

### 2. Four `__`-labelled node kinds are deliberately **not** `:__Meta`

`__JiraProjection`, `__JiraSettings`, `__JiraColumnConfig` and `__ImportRun` all carry the
application namespace and none of them is `:__Meta`.

CLAUDE.md R2 says Tier 2 is "knowledge the import cannot produce" — what a *user* decided. Measured
against that:

| Node | Why not `:__Meta` |
|---|---|
| `__JiraProjection` | Machine-derived from the issue beside it, and regenerable. Storing a derived value is what R2's "Explicitly not `:__Meta`" clause forbids; it exists as a node rather than a property so it is disposable and rebuildable without a re-import |
| `__JiraSettings` | Configuration of a source connection, not annotation of an item. It hangs off nothing in the imported graph, which breaks `:__Meta`'s own invariant |
| `__JiraColumnConfig` | A view layout. R2 names this case and sends it elsewhere: "Saved queries, saved filters, view layouts… give them their own label" |
| `__ImportRun` | A machine's record of a machine's action, pruned to a fixed history length. Nothing a user wrote |

**What this costs, stated plainly.** R2 promises that `MATCH (m:__Meta) DETACH DELETE m` deletes all
application data in one query. That promise is now narrower than it reads: it deletes all
application data *that a user authored*. Four node kinds survive it.

That is the right trade, and the reason is that the four are not the same kind of thing as a
comment. A note on a requirement is irreplaceable — it is the only data in the system a re-import
cannot reconstruct. A projection rebuilds from the issues; settings and column config are a few
rows a user re-enters in a minute; a run history is disposable by definition. The invariant worth
protecting is *"nothing a user typed is lost"*, and it is intact.

The wording in CLAUDE.md R2 should be read as scoped to Tier 2 accordingly. A future clean-slate
query is two statements, not one, and both are safe.

### 3. ag-grid, not the spec's MatTable

Spec §13.2 sketches the Issues table as `MatTable` with `MatPaginator`. ADR 0006 already decided
ag-grid Community as **the** table implementation for this product, and the Req review table is
built on it. A second table library would mean two keyboard models, two sorting behaviours and two
column-resize implementations in one application. CLAUDE.md wins here: table choice is not importer
mechanics or graph schema.

### 4. The credential's scheme is configuration, because Cloud and Data Center disagree

Spec §3.2 mandates `Authorization: Bearer <PAT>` and forbids falling back to Basic on a 401.
Verified against a real Cloud instance (`juanmr82.atlassian.net`): **Cloud answers that exact header
with 403.** It wants `Basic base64(email:apiToken)`.

`jira.auth: bearer | basic` therefore selects the scheme, defaulting to `bearer`. **The §3.2 rule is
untouched**: what it forbids is *retrying* a rejected credential a second way, which sends it twice
and turns one clear failure into two unclear ones. Choosing up front, from configuration, before
the first request, is the opposite of a fallback.

`basic` additionally requires `jira.email`; without it the deployment reads as *not configured*
rather than configured-and-broken, because an API token with no account to pair it with is not a
credential Cloud will accept.

### 5. Cloud has removed `/search`, and the second search path is chosen by its own setting

Also verified live. `GET /rest/api/2/search` answers **410 Gone** on Cloud, pointing at
`/rest/api/2/search/jql`, which differs in ways that reach the design:

- it **refuses unbounded JQL** with a 400 — harmless, since spec §8 already requires project keys;
- it paginates by **cursor** (`nextPageToken`, `isLast`), not by `startAt`/`total`;
- it reports **no total at all**, so a progress bar needs `POST /search/approximate-count` — fetched
  once, best-effort, and never used as a termination condition.

Both loops now exist behind one `searchAll`, and everything downstream of them sees a
`JiraIssuePage` that has had the product-specific paging stripped off.

**This is the one point in this ADR that has been corrected rather than merely recorded.** It
originally said the choice would be made by the existing `jira.auth` signal, on the reasoning that
Cloud is the only product wanting `basic`. That is wrong in the direction that matters: **Data
Center accepts Basic auth too**, so `auth: basic` against a Server host would have silently selected
Cloud's search and failed every import with a 404 naming nothing. Two independent facts get two
settings — `jira.auth` for how the credential is sent, `jira.deployment` for how issues are paged.

They do covary in practice, which makes a mismatch the likeliest misconfiguration here. So preflight
reads the one field that distinguishes the products with certainty — Cloud's `/myself` returns an
`accountId` and no `name`, Data Center exactly the reverse — and warns when the configuration
disagrees with what answered. Detection informs; configuration decides. Auto-detection was rejected
for the same reason it was rejected for auth: it means discovering what a host is by failing against
it.

### 6. `""` is stored, not skipped

Spec §7.1 says to skip empty strings *except* when the property already exists on the node, in
which case it must be **set** to `""`, "because an emptied field is information".

That exception is unreachable as specified. The mapper is a pure function that has never seen the
node, and the downstream mechanism cannot rescue it either: phase 3 removes every key absent from
`presentKeys`, so a skipped `""` is a *removed* property — precisely what the exception forbids.

**`""` is stored unconditionally.** This makes the exception the rule, and matches what `""` has
meant on the DOORS side since the beginning: exists, and is empty (CLAUDE.md §11). The cost is
nil on real data — the committed 50-issue export contains 994 non-empty strings and **not one**
empty one.

### 7. Two orderings in the display projection are inverted from §7.4's table

§7.4 lists its shape rules as a table and says first match wins. Two rows are in an order that
makes the row below them unreachable:

- **`{value, child}` must be checked before `{value}`.** Both carry `value`; only one carries
  `child`. Literal order makes every option-with-child project to its parent alone, and the
  option-with-child row dead code. 135 values in the export.
- **`{displayName}` must be checked before `{name}`.** A JIRA user object carries **both**, and its
  `name` is the *login*. Literal order puts `alovelace` in a column that should read
  `Ada Lovelace` — a login rendered where a person's name belongs, which is the class of leak R5
  exists to prevent. This was not a theoretical risk: it was written the spec's way first and the
  test caught it.

### 8. The value classifier never sees a declared type

Spec §5.1 tabulates value shapes by `schema.type` and §7 is written as rules over that table. The
implementation takes a `JsonElement` and **no catalogue at all**, for three reasons the export
itself demonstrates:

- the type vocabulary is **open** — the export carries `securitylevel`, `comments-page` and
  `sd-approvals`, none of them in §5.1's table, and a plugin adds more. A `when` over known types
  drops a plugin's data *silently*, with the run still reporting success;
- the declared type **does not determine the shape** — `any` holds a string 216 times and an empty
  array 48 times, both correct;
- a field may have **no definition at all**, having been created between the `/field` call and the
  `/search` call. §16.1 requires that this not crash.

The 28 distinct `(type, items, shape)` triples in the export collapse to seven shapes, and a test
drives all 28 through the real fixture to prove the collapse is total. The catalogue survives, but
only for a field's display name and for noticing a field it has never heard of.

### 9. The RBAC seam of §14.1 is **not built**, and that is a debt with a name

§14.1 asks for `security/Authorization.kt`, `requireAdmin { }` on every admin route from day one,
and `GET /api/me`. None of it exists. Its argument is sound — retro-fitting guards onto a live
route tree is how endpoints get missed — but this backend has **no authorization anywhere**, for
DOORS or JIRA, and adding a JIRA-shaped seam would make the JIRA routes look protected while the
module and review write endpoints beside them stayed open.

The seam is one change across the whole route tree, in its own ADR, and it should happen before
this reaches a shared deployment. Recorded here so it is a decision rather than an omission.

### 10. Phase 3 prunes promoted edges, which the spec does not mention

Spec §12 phase 3 lists four writes and no deletion of relationships. Re-assigning an issue then
leaves it with two `assignedTo` edges — `MERGE` adds the new one and nothing removes the old — and
every "issues assigned to X" query keeps answering with the stale one. It is the stale-property bug
the spec spends a page on, one level up, and it is harder to notice because nothing about the node
looks damaged.

Phase 3 therefore deletes the promoted edges it no longer asserts, scoped to a closed list of the
eleven types it owns. `linkedTo` and `subTaskOf` are excluded by name: they belong to phase 4, which
diffs them against the whole run, and pruning them per page would delete every link seen on page one.

### 11. Two Cypher 25 features carry phase 3, and one of them has a subtlety

Two implementation notes that would otherwise read as risky choices:

- **Dynamic labels and relationship types** (`SET n:$(row.label)`, `MERGE (a)-[:$(row.type)]->(b)`)
  collapse what would be nine and eleven near-identical statements into one each. The values come
  from `JiraLabel` and `JiraRel` — compile-time constants — and travel as *parameters*, so no
  attacker-influenced text is ever parsed as Cypher and R10 holds in the way that matters.
- **`REMOVE i[staleKey]`** is the property removal, and it is simpler than the
  `CALL (i, staleKey) { SET i[staleKey] = null }` the spec proposes.

Both were verified against the pinned 2026.06 Community image before being written, and both are
covered by container tests, because whether a server supports them is a property of the server.

One subtlety is load-bearing and easy to lose in a later edit: `UNWIND` of an empty list produces no
rows, so an issue with nothing stale drops out of the statement at that point. Its `MERGE` and `SET`
have already committed, which is why this is correct — but it means nothing may ever be appended
after the `REMOVE`.

## Consequences

- The `:__Meta` delete-everything query no longer covers all application data. Anyone reasoning
  about data lifecycle reads point 2 first.
- A deployment file must state `jira.auth: basic`, `jira.email` **and `jira.deployment: cloud`**
  for a Cloud instance. The packaged defaults are Data Center's, which is what the reference
  instance runs. Setting one and not the other is the failure preflight warns about.
- The fork phase 3 was expected to carry is built: two paging loops, one contract, and one setting
  choosing between them.
- The spec stays as written. It is the record of what was intended; this is the record of what met
  reality, and the two are more useful apart than merged.
- Eleven departures is a lot for six build steps, and most were found by writing a test rather than
  by reading. That is the argument for §16.1's insistence that the mapper be pure and
  fixture-driven: points 6, 7 and 8 are all things a live-instance smoke test would have passed.
