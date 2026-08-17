# Comment threads on the Req review table

**Status:** proposed — extends `docs/features/requirements-review.md` §5.2 and amends `CLAUDE.md`
§9.1/§9.2 (the R7 batch-comment exception).
**Read first:** root `CLAUDE.md` §2 (R1–R3, R5–R8), `docs/adr/0016-authorization-model.md`,
`docs/KEYCLOAK_SETUP.md`, `requirements-review.md` §5.2.

Replaces the single-note Comment cell with a thread: multiple authors, resolve, delete, and — the
reason this needed its own document — inline references to other items and people.

---

## 1. What's changing

| Then (§5.2) | Now |
|---|---|
| Exactly one `:__Note` per item, editable in place | Zero or more `:__Note` per item, chained by `replyTo` |
| No author distinction beyond `__updatedBy` (last editor wins) | Every reply keeps its own author, permanently |
| Batch save: dirty cells across the table, one save icon (R7 amendment, §9.1) | Each reply posts immediately, its own request — see §3 |
| `resolved` — doesn't exist | Boolean on the thread's root note |
| Delete — clear the text to empty | Explicit delete, with confirmation, cascades to replies |
| Author — whatever the backend put in the request context (O5 in the original draft) | The Keycloak `sub` claim, real per ADR 0016 |

The batch-save icon goes away for comments specifically. Once every reply is its own
one-gesture-one-request write, the R7 exception §9.1 carved out stops being necessary — the
table's Comment column reverts to R7's general rule instead of staying the exception to it. Worth
its own line in `CLAUDE.md` when this ships: remove the §9.1 amendment, not just edit this file.

---

## 2. Data model

### 2.1 The thread itself — Shape A, unchanged shape

Still `(:SEItem)-[:__noteOn]->(:__Meta:__Note)`, still kind `note` (R2 Shape A). Two changes to the
payload, both already anticipated in the contract:

| Property | Then | Now |
|---|---|---|
| `replyTo` | reserved, unused ("optional `replyTo` for threading") | a reply's `__metaId` of the thread's root note. Absent on the root. |
| `resolved` | — | boolean, **root note only**. A reply never carries it — the thread has one resolved state, not one per message. |

Flat, one level: a reply's `replyTo` always points at a root, never at another reply. Nothing here
stops nesting later, but nothing asked for it either — see O1.

```cypher
CYPHER 25
// New thread (a root note)
CREATE (n:__Meta:__Note {
  __metaId: $metaId, __metaKind: 'note', __schemaVersion: 2,
  text: $text, resolved: false,
  __createdBy: $sub, __createdAt: $now, __updatedBy: $sub, __updatedAt: $now
})
WITH n
MATCH (i:SEItem {__id: $itemId})
CREATE (i)-[:__noteOn]->(n)

// A reply
CREATE (n:__Meta:__Note {
  __metaId: $metaId, __metaKind: 'note', __schemaVersion: 2,
  text: $text, replyTo: $rootMetaId,
  __createdBy: $sub, __createdAt: $now, __updatedBy: $sub, __updatedAt: $now
})
WITH n
MATCH (i:SEItem {__id: $itemId})
CREATE (i)-[:__noteOn]->(n)
```

`__schemaVersion` moves 1 → 2 — the shape gained two fields. Existing single-note comments still
read fine (`resolved` defaults `false` on read, `replyTo` defaults absent): no migration, just a
version bump so a future reader can tell the generations apart if it ever matters.

**Delete is a cascade, still one guarded write:**

```cypher
CYPHER 25
MATCH (root:__Meta:__Note {__metaId: $rootMetaId})
OPTIONAL MATCH (reply:__Meta:__Note {replyTo: $rootMetaId})
DETACH DELETE root, reply
```

Same class of query as the existing `MATCH (m:__Meta) DETACH DELETE m` full wipe (R2) — bounded to
one thread instead of everything in the graph. Gets the same byte-identical-anchor test every
Tier-2 write gets: assert the `:SEItem` is unchanged before and after.

### 2.2 A name for `__createdBy` — the `:User` cache

R2's contract already has `__createdBy` as a plain string; that doesn't change. What changes is
what the string *is*: the Keycloak `sub` claim (ADR 0016), not a placeholder. A `sub` is an opaque
id — "Added by f47ac10b-58cc…" is useless on screen — and SEC depends on exactly three claims
(`sub`, `groups`, `realm_access.roles`, per `docs/KEYCLOAK_SETUP.md`), so there's no `name` or
`preferred_username` to fall back on for a user who isn't the one currently signed in.

Proposed: a small `:User` node, **its own label — not `:__Meta`, not `:SEItem`.** R2 already carves
this out ("saved queries, saved filters, view layouts… anchor to a user… give them their own
label"). A comment author's display name is the same shape of problem.

```
(:User { __id: sub, __name: <display name at last sign-in>, username: <preferred_username> })
```

Written by `MERGE … SET` from `/api/v1/auth/callback` (a request already happens there) using
whatever the ID token carries beyond the three depended-on claims — `name`, `preferred_username` —
best-effort, overwritten every sign-in so a display-name change in the IdP shows up next login.
**This is a display cache, not an identity store: it never gates a decision.** Group membership and
roles stay exactly where the access-control work put them — Keycloak, checked live, never the
graph (the state-lives-here table in root `CLAUDE.md`). If `:User` disappeared entirely,
authorization would be unaffected; only `__createdBy` on old comments would fall back to showing a
raw `sub`.

`__id = sub` reuses R6 unchanged — `sub` is exactly the kind of globally unique, stable identifier
R6 already asks for, so `:User` gets the same `:ref` addressing as everything else, for free.

---

## 3. Mentions — do we need a rich text editor?

No. What's being asked for — insert a structured reference to another item or a person, without
ever changing how the surrounding text looks — isn't rich text, it's **plain text with typed
tokens**. A real RTE (ProseMirror, TipTap, Quill) buys formatting infrastructure nothing here uses,
at real cost: a document model to serialize, a schema to keep client and server in sync on,
cross-browser `contenteditable` behaviour to fight. None of that is optional once one is pulled
in — it's the whole reason those libraries are the size they are.

### 3.1 Storage — `text` stays a plain string

No payload shape change beyond §2.1. A mention is inline markup inside that same string:

```
@[Elena K.](user:f47ac10b-58cc-4372-a567-0e02b2c3d479)
@[REQ-1042](item:UkVRLTEwNDI)
```

`@[display](kind:ref)` — `kind` is `user` or `item`. Only two kinds, not four: a requirement, a
document and a JIRA ticket are already the same thing to this graph — `:SEItem`, addressed by
`:ref` (R1, R5, R6) — so "link to a requirement, document or ticket" is one case, not three. A user
is the only genuinely new kind, because `:User` isn't an `:SEItem` (§2.2). This is most of why the
feature is smaller than it sounds: three of the four target types were already solved by the
identity scheme this app has used since the first import.

Display text is captured at insert time and never re-resolved — if an item is later renamed, an old
mention keeps reading what it said when written, the same way a quoted requirement's text would.
The `ref` is what stays live; a renderer that wants the current name can always follow it.

### 3.2 Composing — a plain `<textarea>`, not `contenteditable`

`@` or `/` while typing opens a small popup below the caret: type ahead, arrow keys, enter to
insert, escape to dismiss. On insert, splice `@[display](kind:ref)` into the textarea's value at
the caret (`selectionStart`/`selectionEnd`) — a few dozen lines, not a library.

The trade-off worth naming: while composing, the raw token is what's on screen —
`@[Elena K.](user:f47ac…)` — not a pretty chip, until the comment posts and renders read-only. The
nicer version — a live chip *while typing* — needs `contenteditable`, because a `<textarea>` cannot
render anything but characters. That's real complexity (caret handling around an atomic inline
widget, paste sanitisation, serialising back to the stored syntax) for a cosmetic win during the
few seconds before posting. Start without it; revisit only if reviewers actually mind the raw-token
look while typing — cheap to find out for real before building the harder version.

### 3.3 Rendering — parse on read, same seam `Aliases.kt` already runs through

A note's `text`, wherever it's shown read-only (the thread panel, the expanded dialog), goes
through a small parser/pipe that finds `@[…](…)` tokens and swaps each for an inline chip: a
person icon and name for `user`, a type icon and id for `item`, both linking to
`/requirements/item/:ref` (or wherever that `:ref` resolves). An `item` mention whose `:ref`
doesn't resolve gets the same "Not yet imported" treatment the References column already uses for
a dangling `refersTo` (R5's `Aliases.kt`, `requirements-review.md` §5.1) — one more caller of a
pattern that already exists, not a new one.

### 3.4 What ships now vs. later

| Now | Later |
|---|---|
| `text` can hold the `@[…](…)` syntax; the read-only renderer turns it into chips | The `@`/`/` popup itself, and the search behind it (§4) |
| A hand-typed or pasted token already renders correctly | Notifying a mentioned user — its own feature, needs a delivery decision (email? in-app? both?) that doesn't exist yet |

Shipping the storage format and the renderer before the picker means nothing here is throwaway
once the picker lands — there's just nothing yet that *produces* the syntax except someone typing
it by hand.

---

## 4. New backend surface

```
PATCH  /api/v1/annotations/{ref}                   ← already exists; add {"resolved": true|false}
DELETE /api/v1/annotations/{ref}                   ← already exists; now cascades to replies, §2.1
GET    /api/v1/mentions/search?q=&kind=user|item   ← new
```

`mentions/search` is the one genuinely new endpoint, and the one place this feature touches the
access-control work directly: **it's a read path over `:SEItem`, so it carries the R8 visibility
predicate exactly like every other read** (`AccessCypher.visible(alias)`, `graph/Read.kt`) — an
unfiltered mention search would let a query box reveal the name of a requirement someone can't
otherwise see, which is precisely the leak R8 exists to close. `AccessGuardTest` already fails a
statement missing the marker; this endpoint needs to be one more entry it covers, not an exemption.

Backing it needs a full-text index — the thing the JIRA search notes already flagged as "the
answer" once client-side filtering stops being enough (session 21). A cross-graph, type-ahead
search is exactly that trigger:

```cypher
CYPHER 25
CREATE FULLTEXT INDEX mention_search IF NOT EXISTS
FOR (n:SEItem) ON EACH [n.__name]
```

`:User` gets a plain range index on `__name` instead — the set of known users is small enough that
a full-text index would be reaching for a hammer.

---

## 5. "Show resolved threads" — the module-level version

Following on from the earlier decision: this is Shape B, not a browser preference — a per-module
switch, same family as the mandatory-attribute policies:

```
(:DOORSModule)-[:__reviewSettingFor]->(:__Meta:__ReviewSetting { hideResolvedThreads: true })
```

Its own kind rather than overloading `:__Policy`'s `rule` enum (`mandatory` / `forbidden` /
`pattern` — `hidden` doesn't fit that vocabulary; the existing doc already declines to fold
`attributeSetting` into `policy` for exactly this reason).

One node per module, same cardinality as `:__Policy`. Lives in the review settings dialog (the gear
icon), not as a checkbox on the table — set once by whoever's running the review round, read by
everyone looking at that module. A resolved thread's row keeps a comment indicator either way; this
setting only decides whether the full avatar/count chip shows or collapses to a muted "Resolved"
label — never whether the thread is reachable.

---

## 6. Explicit non-goals for this pass

- No text formatting — bold, italic, headings. Confirmed out of scope, not deferred.
- No reply-to-reply nesting. A thread is two levels: root and replies.
- No notification on mention. `@`-ing someone links to them; whether it also pings them is a
  separate decision with its own delivery mechanism.
- No live chip rendering while composing (§3.2) — raw token during editing, chip after posting.
- No change to who can delete or resolve a thread — see O2.

---

## 7. Open questions

- **O1 — nesting.** Flat is assumed. If a review round wants a reply *to* a reply, `replyTo`
  chaining supports it without a schema change — it's a rendering and an ordering decision, not a
  graph one. Worth deciding before it's asked for live, since "flat" is baked into §3.3's renderer.
- **O2 — who may resolve or delete.** Right now: anyone who can write a comment at all. The
  access-control work (ADR 0016) may want this scoped to the thread's own participants, or to a
  role, once groups are the unit of permission everywhere else in the app.
- **O3 — mentioning a user with no `:User` node yet** (never signed in, or signed in before this
  shipped). Search can't find them. Falls back to `sub`-only mentions being impossible for anyone
  who hasn't logged in since this landed — acceptable for v1, worth knowing rather than discovering.
- **O4 — `:User.__name` going stale.** Overwritten on every sign-in (§2.2), so it's only as fresh
  as someone's last login. A comment byline could show a name a person changed months ago if they
  haven't signed back in since. The trade every cache makes; flagging it rather than solving it.
