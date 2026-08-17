package com.sec.graph.cypher

import com.sec.domain.MetaKind.ATTRIBUTE_SETTING as ATTRIBUTE_SETTING_KIND
import com.sec.domain.MetaKind.NOTE as NOTE_KIND
import com.sec.domain.MetaProp.APPLIES_TO_LABELS
import com.sec.domain.MetaProp.ATTRIBUTE_NAME
import com.sec.domain.MetaProp.REPLY_TO
import com.sec.domain.MetaProp.RESOLVED
import com.sec.domain.MetaProp.RULE
import com.sec.domain.MetaProp.TEXT
import com.sec.domain.MetaProp.EXCLUDED_FROM_OPEN_POINTS
import com.sec.domain.MetaProp.VERIFICATION
import com.sec.domain.MetaProp.VISIBLE
import com.sec.domain.MetaValue.CURRENT_SCHEMA_VERSION
import com.sec.domain.MetaValue.MANDATORY_RULE
import com.sec.domain.NodeLabel.ATTRIBUTE_SETTING
import com.sec.domain.NodeLabel.DELETED
import com.sec.domain.NodeLabel.META
import com.sec.domain.NodeLabel.NOTE
import com.sec.domain.NodeLabel.POLICY
import com.sec.domain.NodeLabel.SE_ITEM
import com.sec.domain.NodeLabel.UNDEFINED
import com.sec.domain.NodeLabel.USER
import com.sec.domain.Prop.CREATED_AT
import com.sec.domain.Prop.CREATED_BY
import com.sec.domain.Prop.ID
import com.sec.domain.Prop.META_ID
import com.sec.domain.Prop.META_KIND
import com.sec.domain.Prop.MODULE_URL
import com.sec.domain.Prop.NAME
import com.sec.domain.Prop.SCHEMA_VERSION
import com.sec.domain.Prop.SORT_KEY
import com.sec.domain.Prop.UPDATED_AT
import com.sec.domain.Prop.UPDATED_BY
import com.sec.domain.Rel.ATTRIBUTE_SETTING_FOR
import com.sec.domain.Rel.NOTE_ON
import com.sec.domain.Rel.POLICY_FOR
import com.sec.source.doors.DoorsAttr.ID as DOORS_ID
import com.sec.source.doors.DoorsLabel.MODULE as DOORS_MODULE
import com.sec.source.doors.DoorsLabel.OBJECT as DOORS_OBJECT
import com.sec.source.doors.DoorsLabel.REQUIREMENT as DOORS_REQUIREMENT
import com.sec.source.doors.DoorsRel.REFERS_TO

// Cypher for docs/REQ_REVIEW.md — the Req review table, its references and its comments.
// Every statement is CYPHER 25-prefixed and parameterised; the transaction timeout is applied to
// every session in graph/Read.kt and graph/Write.kt, so nothing here can be issued without one.
//
// Every graph name is interpolated from a constant (ADR 0010). A bare $NAME is a *name*; the
// escaped form is a query *parameter*.
public object ReviewCypher {

    // One row per object of a module, in DOORS document order.
    //
    // ORDER BY __sortKey, never objectNumber: the outline number does not sort correctly as a
    // string, which is the entire reason __sortKey exists (R3, CLAUDE.md §11).
    //
    // References are collected in the same statement rather than in a second round trip per row —
    // 984 rows would otherwise be 984 queries. `resolved` is false when the target is a
    // placeholder the importer created for an object no import has reached yet; the frontend
    // renders those as "Not yet imported" and does not link them (§5.1).
    //
    // Incoming links are deliberately included here and are *incomplete by design*: importers
    // ingest out-links only, so an incoming edge exists only when the referencing module has
    // itself been imported. That caveat is surfaced in the UI, never silently (SE_ITEM_SCHEMA §8.2).
    //
    // `deleted` is the other thing a reference can be, and it is not a kind of unresolved: the
    // target is a real imported object with its `id` and its text, which a later export of its own
    // module stopped containing. DOORS deleted it and left this link behind. So `resolved` stays
    // true and the row still shows what it points at — what changes is that the link itself is the
    // defect, and the only fix is in DOORS (ADR 0012).
    // Every reference is filtered at *both* ends (spec §7): a link whose far end this caller may
    // not see is absent from the row entirely — not struck through, not "unresolved", not counted
    // among the row's Issues. The predicate therefore sits inside each pattern comprehension's own
    // WHERE, which is the easiest place in this file to forget it: filtering only the outer MATCH
    // would still disclose a hidden object's DOORS id and the url of the module it belongs to.
    //
    // NOT o:$DELETED throughout: an object DOORS deleted is not part of the module any more, and
    // a module listing that still contained it would be showing a document DOORS does not have.
    // It stays in the graph only as the far end of the links DOORS left behind, and it is reached
    // from those links -- never by listing the module (ADR 0012).
    //
    // `val`, not `const val`: the ACL clause below is a function call (AccessCypher.visible), not
    // a compile-time constant, so this statement can only be computed once at object-init time
    // rather than at compile time. Every name in it — including the ones the predicate embeds —
    // is still a single interpolated constant (ADR 0010); only the *mechanism* differs.
    //
    // `participants` is up to 3 distinct comment authors, display-name resolved, for the Comment
    // column's compact chip (docs/req-review-comment-threads.md §5 redesign) — it lets the cell
    // show who is in a thread without a reviewer opening it. Full note text is deliberately *not*
    // here: the panel's own `GET .../annotations` is where a message is actually read, so the list
    // endpoint stays cheap however long a thread gets.
    public val MODULE_OBJECTS: String = """
        CYPHER 25
        MATCH (o:$DOORS_OBJECT {$MODULE_URL: ${'$'}moduleUrl})
        WHERE NOT o:$DOORS_MODULE AND NOT o:$DELETED AND ${AccessCypher.visible("o")}
        WITH o
        ORDER BY o.$SORT_KEY
        SKIP ${'$'}skip
        LIMIT ${'$'}limit
        WITH o,
             [(o)-[:$REFERS_TO]->(out:$SE_ITEM) WHERE ${AccessCypher.visible("out")} | {
                 ref: out.$ID,
                 id: CASE WHEN out:$UNDEFINED THEN null ELSE coalesce(out.$DOORS_ID, out.$NAME) END,
                 resolved: NOT out:$UNDEFINED,
                 deleted: out:$DELETED,
                 moduleUrl: out.$MODULE_URL
             }] AS outgoing,
             [(o)<-[:$REFERS_TO]-(inc:$SE_ITEM) WHERE ${AccessCypher.visible("inc")} | {
                 ref: inc.$ID,
                 id: CASE WHEN inc:$UNDEFINED THEN null ELSE coalesce(inc.$DOORS_ID, inc.$NAME) END,
                 resolved: NOT inc:$UNDEFINED,
                 deleted: inc:$DELETED,
                 moduleUrl: inc.$MODULE_URL
             }] AS incoming,
             [(o)-[:$NOTE_ON]->(n:$META:$NOTE) | n] AS notes
        WITH o, outgoing, incoming, notes,
             [n IN notes WHERE n.$REPLY_TO IS NULL | n.$META_ID][0]  AS threadRootId,
             [n IN notes WHERE n.$REPLY_TO IS NULL | n.$RESOLVED][0] AS threadResolved,
             size(notes)                                             AS threadCount,
             reduce(latest = null, n IN notes |
               CASE WHEN latest IS NULL OR n.$UPDATED_AT > latest THEN n.$UPDATED_AT ELSE latest END
             ) AS threadUpdatedAt,
             // Distinct authors, first-seen order — capped to 3 before the :User join below, so
             // the join runs on the participant chip's own budget rather than on the thread's full
             // length. Compact cell display only; the panel's full thread still resolves every
             // author through READ_ANNOTATIONS.
             reduce(ids = [], n IN notes |
               CASE WHEN n.$CREATED_BY IN ids THEN ids ELSE ids + n.$CREATED_BY END
             )[0..3] AS participantIds
        UNWIND (CASE WHEN participantIds = [] THEN [null] ELSE participantIds END) AS participantId
        OPTIONAL MATCH (u:$USER {$ID: participantId}) WHERE participantId IS NOT NULL
        WITH o, outgoing, incoming, threadRootId, threadResolved, threadCount, threadUpdatedAt,
             collect(
               CASE WHEN participantId IS NULL THEN null ELSE coalesce(u.$NAME, participantId) END
             ) AS participantNames
        RETURN o                AS object,
               labels(o)        AS labels,
               outgoing         AS outgoing,
               incoming         AS incoming,
               threadRootId     AS threadRootId,
               threadResolved   AS threadResolved,
               threadCount      AS threadCount,
               threadUpdatedAt  AS threadUpdatedAt,
               [x IN participantNames WHERE x IS NOT NULL] AS participants
    """

    /**
     * The module's mandatory-attribute policies (`attribute-policy-checks.md` §4, step 1).
     *
     * Read once per request and evaluated against each row's property map in Kotlin — the row
     * query above already returns every property of every object, so checking them costs a map
     * lookup per (object × mandatory attribute) and no second scan of the module.
     *
     * **The result is never stored.** A violation is a function of (imported data × policy), and
     * the policy is Tier-2 configuration a user changes from the settings dialog at any moment —
     * so it is not a property of the import and cannot be computed at import time without going
     * stale on the next checkbox. R2 excludes derived data from `:__Meta` for exactly this reason.
     *
     * `appliesToLabels` is read, never assumed: a policy that applies to everything is a policy
     * nobody can reason about (CLAUDE.md R2). The default matches the one the write path stores.
     */
    public val MANDATORY_POLICIES: String = """
        CYPHER 25
        MATCH (m:$DOORS_MODULE {$ID: ${'$'}moduleId})-[:$POLICY_FOR]->(p:$META:$POLICY)
        WHERE p.$RULE = '$MANDATORY_RULE' AND ${AccessCypher.visible("m")}
        RETURN p.$ATTRIBUTE_NAME                                    AS attributeName,
               coalesce(p.$APPLIES_TO_LABELS, ['$DOORS_REQUIREMENT']) AS appliesToLabels
    """

    // Names for the modules a page's references point into, fetched once for the whole page rather
    // than joined per reference. An unresolved target names a module that has usually *not* been
    // imported, in which case there is no name to find and the UI says "Not yet imported" without
    // one — but when the module is present, naming it is what makes the message actionable (§5.1).
    public val MODULE_NAMES: String = """
        CYPHER 25
        UNWIND ${'$'}moduleIds AS moduleId
        MATCH (m:$DOORS_MODULE {$ID: moduleId})
        WHERE ${AccessCypher.visible("m")}
        RETURN m.$ID AS id, m.$NAME AS name
    """

    // Counted separately from the page so the client can show "n of m" without holding every row.
    // `val`, not `const val` — same reason as MODULE_OBJECTS above.
    public val COUNT_MODULE_OBJECTS: String = """
        CYPHER 25
        MATCH (o:$DOORS_OBJECT {$MODULE_URL: ${'$'}moduleUrl})
        WHERE NOT o:$DOORS_MODULE AND NOT o:$DELETED AND ${AccessCypher.visible("o")}
        RETURN count(o) AS total
    """

    // One item for the detail panel (§7). The module is returned as its __name so the panel can
    // render __moduleUrl as a link labelled with the module's name, per the R5 alias map.
    public val ITEM_DETAIL: String = """
        CYPHER 25
        MATCH (i:$SE_ITEM {$ID: ${'$'}itemId})
        WHERE ${AccessCypher.visible("i")}
        OPTIONAL MATCH (m:$DOORS_MODULE {$ID: i.$MODULE_URL})
          WHERE ${AccessCypher.visible("m")}
        RETURN i          AS item,
               labels(i)  AS labels,
               m.$NAME    AS moduleName,
               m.$ID      AS moduleId
        LIMIT 1
    """

    public val ITEM_TRACES_OUT: String = """
        CYPHER 25
        MATCH (i:$SE_ITEM {$ID: ${'$'}itemId})-[:$REFERS_TO]->(t:$SE_ITEM)
        WHERE ${AccessCypher.visible("i")} AND ${AccessCypher.visible("t")}
        RETURN t.$ID        AS ref,
               CASE WHEN t:$UNDEFINED THEN null ELSE coalesce(t.$DOORS_ID, t.$NAME) END AS id,
               NOT t:$UNDEFINED         AS resolved,
               t:$DELETED               AS deleted,
               t.$MODULE_URL            AS moduleUrl
        ORDER BY id
        LIMIT ${'$'}limit
    """

    public val ITEM_TRACES_IN: String = """
        CYPHER 25
        MATCH (i:$SE_ITEM {$ID: ${'$'}itemId})<-[:$REFERS_TO]-(t:$SE_ITEM)
        WHERE ${AccessCypher.visible("i")} AND ${AccessCypher.visible("t")}
        RETURN t.$ID        AS ref,
               CASE WHEN t:$UNDEFINED THEN null ELSE coalesce(t.$DOORS_ID, t.$NAME) END AS id,
               NOT t:$UNDEFINED         AS resolved,
               t:$DELETED               AS deleted,
               t.$MODULE_URL            AS moduleUrl
        ORDER BY id
        LIMIT ${'$'}limit
    """

    // --- Comment threads (Tier 2, Shape A) ------------------------------------------------------
    //
    // docs/req-review-comment-threads.md. One thread per item — root plus flat replies, `resolved`
    // on the root only. Posting is a read-then-branch, not a single MERGE: READ_THREAD_ROOT decides
    // whether a new note is the thread's root or a reply to the existing one, the same way the old
    // single-note write enforced "exactly one" via a relationship MERGE. `val`, not `const val`, on
    // every statement that embeds AccessCypher.visible — see MODULE_OBJECTS above for why.

    /**
     * Does this item already have a thread? Distinguishes "item not found or not visible" (empty
     * result) from "item visible, no thread yet" (one row, `rootMetaId` null) — [MetaWriter.postNote]
     * needs both answers to decide whether to create a root or a reply, and whether to 404 at all.
     */
    public val READ_THREAD_ROOT: String = """
        CYPHER 25
        MATCH (i:$SE_ITEM {$ID: ${'$'}itemId})
        WHERE ${AccessCypher.visible("i")}
        OPTIONAL MATCH (i)-[:$NOTE_ON]->(root:$META:$NOTE) WHERE root.$REPLY_TO IS NULL
        RETURN root.$META_ID AS rootMetaId
    """

    /**
     * One note, root or reply. `${'$'}replyTo` null creates a root (Neo4j removes a property set to
     * null, so [MetaProp.REPLY_TO] ends up genuinely absent — spec §2.1). `${'$'}extra` carries
     * `{resolved: false}` for a root and `{}` for a reply, so the one conditional field is a plain
     * map merge rather than a second statement (the `SET n += $props` idiom this file already uses).
     *
     * Returns the full note, `:User`-joined, so [MetaWriter.postNote] needs no second round trip —
     * `${'$'}user` here is the Keycloak `sub` (spec §2.2), the same id [UserCypher.UPSERT] keys on.
     */
    public val CREATE_NOTE: String = """
        CYPHER 25
        MATCH (i:$SE_ITEM {$ID: ${'$'}itemId})
        WHERE ${AccessCypher.visible("i")}
        CREATE (n:$META:$NOTE {
          $META_ID: ${'$'}metaId, $META_KIND: '$NOTE_KIND', $SCHEMA_VERSION: $CURRENT_SCHEMA_VERSION,
          $TEXT: ${'$'}text,
          $CREATED_BY: ${'$'}user, $CREATED_AT: ${'$'}now, $UPDATED_BY: ${'$'}user, $UPDATED_AT: ${'$'}now
        })
        SET n.$REPLY_TO = ${'$'}replyTo, n += ${'$'}extra
        CREATE (i)-[:$NOTE_ON]->(n)
        WITH n
        OPTIONAL MATCH (u:$USER {$ID: n.$CREATED_BY})
        RETURN n.$META_ID AS metaId, n.$TEXT AS text, n.$REPLY_TO AS replyTo, n.$RESOLVED AS resolved,
               coalesce(u.$NAME, n.$CREATED_BY) AS authorName, n.$CREATED_AT AS createdAt,
               n.$UPDATED_AT AS updatedAt
    """

    /**
     * Every note on one item, root first then chronologically — `__metaId` is UUIDv7
     * (`domain/UuidV7.kt`), so a plain `ORDER BY` on it needs no second sort key. Joins `:User` for
     * the display name, falling back to the raw `sub` when nobody with that id has ever signed in
     * (O3): a display cache that cannot answer must not hide the author entirely.
     */
    public val READ_ANNOTATIONS: String = """
        CYPHER 25
        MATCH (i:$SE_ITEM {$ID: ${'$'}itemId})-[:$NOTE_ON]->(n:$META:$NOTE)
        WHERE ${AccessCypher.visible("i")}
        OPTIONAL MATCH (u:$USER {$ID: n.$CREATED_BY})
        RETURN n.$META_ID                    AS metaId,
               n.$TEXT                       AS text,
               n.$REPLY_TO                   AS replyTo,
               n.$RESOLVED                   AS resolved,
               coalesce(u.$NAME, n.$CREATED_BY) AS authorName,
               n.$CREATED_AT                 AS createdAt,
               n.$UPDATED_AT                 AS updatedAt
        ORDER BY n.$META_ID
    """

    // Root only — a reply has no resolved state of its own (spec §2.1), so this matches nothing
    // for one and the route reports that the same way it reports "not found". Returns the full
    // note, :User-joined, the same shape CREATE_NOTE does — the route has only the note's own ref,
    // never the anchor item's, so a second query could not be scoped by item even if it wanted to.
    public val RESOLVE_NOTE: String = """
        CYPHER 25
        MATCH (i:$SE_ITEM)-[:$NOTE_ON]->(root:$META:$NOTE {$META_ID: ${'$'}metaId})
        WHERE root.$REPLY_TO IS NULL AND ${AccessCypher.visible("i")}
        SET root.$RESOLVED = ${'$'}resolved, root.$UPDATED_BY = ${'$'}user, root.$UPDATED_AT = ${'$'}now
        WITH root
        OPTIONAL MATCH (u:$USER {$ID: root.$CREATED_BY})
        RETURN root.$META_ID AS metaId, root.$TEXT AS text, root.$REPLY_TO AS replyTo,
               root.$RESOLVED AS resolved, coalesce(u.$NAME, root.$CREATED_BY) AS authorName,
               root.$CREATED_AT AS createdAt, root.$UPDATED_AT AS updatedAt
    """

    /**
     * Deletes one thread, or one reply — the same statement either way. A reply's own `__metaId`
     * never appears as another note's `replyTo` (threads are flat), so when `${'$'}metaId` names a
     * reply the `OPTIONAL MATCH` below simply matches nothing and only that reply is removed. When
     * it names a root, every reply chained to it goes too — the cascade the spec's §2.1 shows.
     */
    public val DELETE_NOTE: String = """
        CYPHER 25
        MATCH (i:$SE_ITEM)-[:$NOTE_ON]->(root:$META:$NOTE {$META_ID: ${'$'}metaId})
        WHERE ${AccessCypher.visible("i")}
        WITH i, root, ${'$'}metaId AS deletedId
        OPTIONAL MATCH (i)-[:$NOTE_ON]->(reply:$META:$NOTE {$REPLY_TO: ${'$'}metaId})
        DETACH DELETE root, reply
        RETURN deletedId
    """

    // --- Attribute settings (Tier 2, Shape B) ---------------------------------------------------

    public val EXISTING_ATTRIBUTE_SETTINGS: String = """
        CYPHER 25
        MATCH (m:$DOORS_MODULE {$ID: ${'$'}moduleId})-[:$ATTRIBUTE_SETTING_FOR]->(s:$META:$ATTRIBUTE_SETTING)
        WHERE ${AccessCypher.visible("m")}
        RETURN s.$ATTRIBUTE_NAME AS name,
               coalesce(s.$VISIBLE, false)      AS visible,
               coalesce(s.$VERIFICATION, false) AS verification,
               // coalesce, because every setting node written before this flag existed carries no
               // such property — and an attribute nobody excluded is not excluded.
               coalesce(s.$EXCLUDED_FROM_OPEN_POINTS, false) AS excludedFromOpenPoints
    """

    // One node per (module, attributeName) — MERGE on attributeName is what enforces it, since
    // Community has no composite constraint to lean on.
    public val UPSERT_ATTRIBUTE_SETTINGS: String = """
        CYPHER 25
        MATCH (m:$DOORS_MODULE {$ID: ${'$'}moduleId})
        WHERE ${AccessCypher.visible("m")}
        UNWIND ${'$'}settings AS row
        MERGE (m)-[:$ATTRIBUTE_SETTING_FOR]->(s:$META:$ATTRIBUTE_SETTING {$ATTRIBUTE_NAME: row.attributeName})
          ON CREATE SET s.$META_ID    = row.metaId,
                        s.$CREATED_BY = ${'$'}user,
                        s.$CREATED_AT = ${'$'}now
        SET s.$META_KIND      = '$ATTRIBUTE_SETTING_KIND',
            s.$SCHEMA_VERSION = $CURRENT_SCHEMA_VERSION,
            s.$VISIBLE        = row.visible,
            s.$VERIFICATION   = row.verification,
            s.$EXCLUDED_FROM_OPEN_POINTS = row.excludedFromOpenPoints,
            s.$UPDATED_BY     = ${'$'}user,
            s.$UPDATED_AT     = ${'$'}now
    """

    // An attribute set back to all-false carries no information, so its node goes rather than
    // lingering as a row of false — same reasoning as an emptied comment.
    public val DELETE_ATTRIBUTE_SETTINGS: String = """
        CYPHER 25
        MATCH (m:$DOORS_MODULE {$ID: ${'$'}moduleId})-[:$ATTRIBUTE_SETTING_FOR]->(s:$META:$ATTRIBUTE_SETTING)
        WHERE s.$ATTRIBUTE_NAME IN ${'$'}names AND ${AccessCypher.visible("m")}
        DETACH DELETE s
    """
}
