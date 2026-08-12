package com.sec.domain

/**
 * Every source-agnostic name the backend uses to address the graph, in one place.
 *
 * This file is the single source of truth for the `__` namespace (R1) and for the labels,
 * relationship types and payload keys of Tier 2 (R2). Source-specific vocabulary does **not**
 * live here — DOORS attribute and label names are in `source/doors/DoorsNames.kt`, and a future
 * Windchill or CAMEO source gets its own, so that adding a source touches no existing one (R3).
 *
 * ## Nothing addresses the graph by literal — not Kotlin, and not the Cypher
 *
 * Every name here is interpolated into the statements in `graph/cypher/` and `meta/MetaSchema.kt`,
 * so renaming one is **one edit, in this file**. Nothing else in the backend has to be found.
 *
 * The Cypher used to spell these out, on the argument that renaming `__id` means changing Python
 * and re-importing every module anyway, so the Kotlin edit was a rounding error. That reasoning
 * held for the cost of the rename and missed the cost of *finding* it: `__id` appeared 58 times
 * across eight statement files, and a reader could not tell which occurrences were the same
 * decision. See ADR 0010, amended.
 *
 * Two guards in `GraphNamesTest` keep it that way — every name the compiled statements carry is
 * declared here, and no statement *source* contains a graph name written out. The second is what
 * stops the literals creeping back one statement at a time.
 */

/**
 * Property names in the `__` namespace: Tier 1, derived by the importers, and the Tier-2 contract
 * every `:__Meta` node carries.
 *
 * Names *without* the prefix are verbatim source data and belong to a source's own names file.
 * The one exception is [MetaProp], whose payload keys are deliberately un-prefixed (R2).
 */
public object Prop {
    /**
     * The namespace itself. Anything starting with this is ours and never reaches the user (R5) —
     * which is what the runtime attribute-discovery query filters on.
     */
    public const val NAMESPACE: String = "__"

    // --- Tier 1: regenerable by a re-import over the same source file ---

    /** Application identity, globally unique. Never a source system's own id (R6). */
    public const val ID: String = "__id"
    public const val NAME: String = "__name"
    public const val VERSION: String = "__version"

    /** A plain string sort on this reproduces the source tool's own display order (R3). */
    public const val SORT_KEY: String = "__sortKey"
    public const val MODULE_URL: String = "__moduleUrl"
    public const val OBJECT_URL: String = "__objectUrl"

    /** The source's own type wording, preferred over a label chip whenever it is present (R5). */
    public const val TYPE_RAW: String = "__typeRaw"

    // --- Tier 2: the contract of every :__Meta node (R2) ---

    public const val META_ID: String = "__metaId"
    public const val META_KIND: String = "__metaKind"

    /**
     * Payload generation. Tier 2 is the only data in the system a re-import cannot fix, so this is
     * set on every meta node from the first one written.
     */
    public const val SCHEMA_VERSION: String = "__schemaVersion"
    public const val CREATED_BY: String = "__createdBy"
    public const val CREATED_AT: String = "__createdAt"
    public const val UPDATED_BY: String = "__updatedBy"
    public const val UPDATED_AT: String = "__updatedAt"
}

/**
 * Relationship types.
 *
 * [CHILD] is the one hierarchy relationship for every source (R3) — there is no
 * `__windchillChild` and there never will be. The rest are Tier-2 anchors, and each is what makes
 * the far end deletable by the single `MATCH (m:__Meta) DETACH DELETE m` query.
 *
 * Source-native relationships keep their own un-prefixed names and live with their source.
 */
public object Rel {
    public const val CHILD: String = "__child"

    // Shape A — annotation on one item
    public const val NOTE_ON: String = "__noteOn"
    public const val TAGGED_AS: String = "__taggedAs"
    public const val REVIEW_OF: String = "__reviewOf"
    public const val FLAG_ON: String = "__flagOn"
    public const val CLASSIFIED_AS: String = "__classifiedAs"

    // Shape B — a rule scoped to a set
    public const val POLICY_FOR: String = "__policyFor"
    public const val ATTRIBUTE_SETTING_FOR: String = "__attributeSettingFor"

    // Shape C — a reified user-drawn link
    public const val LINK_FROM: String = "__linkFrom"
    public const val LINK_TO: String = "__linkTo"
}

/**
 * Source-agnostic node labels: the one label every item carries, the importer's placeholder, and
 * the Tier-2 labels.
 *
 * Type labels belong to their source ([com.sec.source.doors.DoorsLabel] and friends).
 */
public object NodeLabel {
    /** Carried by every imported item of every source. The only thing a new source joins on. */
    public const val SE_ITEM: String = "SEItem"

    /**
     * An object something links to that no import has reached yet. Written by the importers, so
     * it is Tier 1 despite the prefix, and it renders as *Not yet imported* (R5).
     */
    public const val UNDEFINED: String = "__UNDEFINED"

    /**
     * An object an import once brought in that a later export of the same module no longer
     * contains: DOORS deleted it, and DOORS kept the links pointing at it.
     *
     * Written by the importers, so it is Tier 1 despite the prefix — but it is the one Tier-1
     * name that is not a function of a single export. It is a function of two: this one, and
     * whatever the graph already held. Re-importing an export that contains the object removes
     * the label again.
     *
     * It sits **alongside** the labels the object already had rather than replacing them, which
     * is why nothing in this backend needs a second read path for a deleted object: it is still
     * a `:DOORSRequirement`, it still has its `id` and its `Object Text`, and every projection
     * that could describe it before can still describe it. What changes is that it is out of the
     * tree and that anything still linking to it is a defect to be fixed in DOORS (ADR 0012).
     */
    public const val DELETED: String = "__DELETED"

    /**
     * One execution of one importer — start, phases, counters, outcome.
     *
     * Application-owned and **deliberately not `:__Meta`**. Meta is knowledge a user contributed
     * that no import can reproduce; this is the opposite — a machine's record of a machine's
     * action, of interest only until the next few runs replace it, and pruned to a fixed history
     * length rather than kept. Filing it under `:__Meta` would put a growing pile of operational
     * records inside the one thing `MATCH (m:__Meta) DETACH DELETE m` is meant to mean: "delete
     * everything the users wrote". See ADR 0014 for what that costs and why it is still right.
     */
    public const val IMPORT_RUN: String = "__ImportRun"

    /**
     * The label that decides Tier 1 from Tier 2 — not the `__` prefix, which both tiers carry.
     * Owns the uniqueness constraint on [Prop.META_ID].
     */
    public const val META: String = "__Meta"

    public const val NOTE: String = "__Note"
    public const val TAG: String = "__Tag"
    public const val REVIEW: String = "__Review"
    public const val FLAG: String = "__Flag"
    public const val CLASSIFICATION: String = "__Classification"
    public const val POLICY: String = "__Policy"
    public const val ATTRIBUTE_SETTING: String = "__AttributeSetting"
    public const val LINK: String = "__Link"

    /** Every Tier-2 label, second-label first. Used by the Cypher guard test. */
    public val meta: Set<String> = setOf(
        META, NOTE, TAG, REVIEW, FLAG, CLASSIFICATION, POLICY, ATTRIBUTE_SETTING, LINK,
    )
}

/**
 * Values of [Prop.VERSION], which every source writes and every view reads.
 *
 * Source-agnostic and here rather than in a source's names file, because DOORS and JIRA both write
 * [CURRENT] and [Aliases.renderVersionValue] compares against it — three places for one string is
 * how two of them come to disagree.
 */
public object ItemVersion {
    /**
     * The item as the source holds it now, which is everything imported so far.
     *
     * Deliberately **not** called a baseline: a DOORS baseline is a frozen, numbered release of a
     * module, which this is not, and which will need the word when it arrives (R5).
     */
    public const val CURRENT: String = "current"
}

/**
 * Properties of a [NodeLabel.IMPORT_RUN] node.
 *
 * Un-prefixed inside a `__`-labelled node, the same way a `:__Meta` payload is: the label already
 * says whose the node is, and prefixing the payload as well would say it twice.
 *
 * Source-agnostic, and that is the whole point of the framework these belong to — nothing here may
 * ever learn what a DOORS module or a JIRA project is. [IMPORTER_ID] is the only place a run says
 * which source it was, and it says it as a string.
 */
public object ImportRunProp {
    public const val IMPORTER_ID: String = "importerId"
    public const val STATUS: String = "status"
    public const val STARTED_AT: String = "startedAt"
    public const val FINISHED_AT: String = "finishedAt"

    /** The phase the run is in, or the one it stopped in. */
    public const val PHASE: String = "phase"

    /**
     * What the run was asked to do, as JSON text — for JIRA, the exact JQL and page size used.
     *
     * JSON text rather than a property per key because the keys are the importer's business and
     * this file must not learn them. It is read by people reconstructing what a past run did, not
     * by a query, so nothing is lost by it being opaque to Cypher.
     */
    public const val PARAMS: String = "params"

    /** What the run did, as JSON text, for the same reason [PARAMS] is. */
    public const val COUNTERS: String = "counters"

    public const val WARNINGS: String = "warnings"

    /** Message and exception class. **Never a stack trace** — that goes to the log, not the graph. */
    public const val ERROR: String = "error"
}

/**
 * The closed `__metaKind` enum (R2). An unknown kind is a 400 at the API boundary, never a
 * silently accepted node — the same discipline the importer applies to `Object Type`.
 *
 * Each value mirrors the second label on [NodeLabel]; [labels] states that pairing once so no
 * write path can drift from it.
 */
public object MetaKind {
    public const val NOTE: String = "note"
    public const val TAG: String = "tag"
    public const val REVIEW: String = "review"
    public const val FLAG: String = "flag"
    public const val CLASSIFICATION: String = "classification"
    public const val POLICY: String = "policy"
    public const val ATTRIBUTE_SETTING: String = "attributeSetting"
    public const val LINK: String = "link"

    public val labels: Map<String, String> = mapOf(
        NOTE to NodeLabel.NOTE,
        TAG to NodeLabel.TAG,
        REVIEW to NodeLabel.REVIEW,
        FLAG to NodeLabel.FLAG,
        CLASSIFICATION to NodeLabel.CLASSIFICATION,
        POLICY to NodeLabel.POLICY,
        ATTRIBUTE_SETTING to NodeLabel.ATTRIBUTE_SETTING,
        LINK to NodeLabel.LINK,
    )

    public val all: Set<String> = labels.keys

    public fun isKnown(kind: String): Boolean = kind in all
}

/**
 * Payload keys on a `:__Meta` node.
 *
 * Deliberately **without** the `__` prefix (R2): the prefix marks the contract fields in [Prop],
 * and the payload is what a kind is actually about. Only the keys the backend writes today are
 * declared — the rest of the catalogue arrives with the view that needs it.
 */
public object MetaProp {
    /** `:__Classification` — which axis of the controlled vocabulary this places the item on. */
    public const val SCHEME: String = "scheme"

    /** `:__Classification` — validated against a closed enum; the display label is never stored. */
    public const val CODE: String = "code"

    /** `:__Policy` — `mandatory` / `forbidden` / `pattern`. */
    public const val RULE: String = "rule"

    /** `:__Policy` and `:__AttributeSetting` — the attribute the rule or the role is about. */
    public const val ATTRIBUTE_NAME: String = "attributeName"

    /** `:__Policy` — always stored, never implied. A policy that applies to everything is one
     *  nobody can reason about. */
    public const val APPLIES_TO_LABELS: String = "appliesToLabels"

    /** `:__Note` — the comment itself. */
    public const val TEXT: String = "text"

    /** `:__AttributeSetting` — shown as a column in the review table. */
    public const val VISIBLE: String = "visible"

    /** `:__AttributeSetting` — this attribute is how the requirement will be shown to be met. */
    public const val VERIFICATION: String = "verification"

    /**
     * `:__AttributeSetting` — the TBD / TBC scan skips this attribute's value.
     *
     * A *role for an attribute*, like [VISIBLE] and [VERIFICATION], which is why it belongs on the
     * setting node and not on a `:__Policy` (R2: a policy models a rule about a *value*, and this
     * says nothing about what the value may be).
     *
     * Named for what it excludes from rather than for the control that sets it: the scan is over
     * open points (`requirements-statistics.md` §3.3), and TBD / TBC is what an open point looks
     * like in DOORS. The user-facing wording lives in `Aliases`.
     */
    public const val EXCLUDED_FROM_OPEN_POINTS: String = "excludedFromOpenPoints"
}

/** Values of controlled vocabularies stored in a meta payload. Wording comes from [Aliases]. */
public object MetaValue {
    /** The [MetaProp.SCHEME] of the L0–L4 classification on a module. */
    public const val SYSTEM_LEVEL_SCHEME: String = "systemLevel"

    /** The [MetaProp.RULE] of a mandatory-attribute policy. */
    public const val MANDATORY_RULE: String = "mandatory"

    /**
     * What [Prop.SCHEMA_VERSION] is set to on every meta node written today.
     *
     * A number rather than a name, and here rather than inline in four write statements, because
     * the day a payload shape changes this is the value that has to move — and a generation
     * number that is only right in three of four places is worse than no generation number.
     */
    public const val CURRENT_SCHEMA_VERSION: Int = 1
}
