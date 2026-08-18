package com.sec.source.doors

/**
 * Every DOORS-specific name the backend uses, in one place.
 *
 * Nothing DOORS-specific may exist outside this package and the DOORS-specific API routes
 * (CLAUDE.md §1), so a second source adds `source/windchill/WindchillNames.kt` beside this and
 * touches nothing here. The source-agnostic half — the `__` namespace, `:SEItem`, Tier 2 — is in
 * `domain/GraphNames.kt`.
 *
 * ## [DoorsAttr] is interpolated into Cypher; [DoorsLabel] and [DoorsProp] are not
 *
 * The three are named the same way and are not equally volatile, which is the whole reason they
 * are separate objects:
 *
 *  - **[DoorsAttr] is the source tool's vocabulary.** A DOORS administrator can rename
 *    `Object Text` in the module's attribute definitions, and the importer would not need a line
 *    changed for it — it copies attributes verbatim. So this is the one rename that is genuinely
 *    cheap at the source and must therefore be cheap here: these constants are interpolated into
 *    the Cypher, and a rename is one edit in this file.
 *  - **[DoorsProp] and [DoorsLabel] are ours.** The importer derives them, so renaming one means
 *    changing Python, re-importing every module and amending
 *    `docs/DOORS_TO_NEO4J_IMPORTER_SPEC.md`. The Cypher spells those out for readability, and
 *    `GraphNamesTest` fails the build on any that is not declared here.
 *
 * See ADR 0010.
 */

/**
 * Verbatim DOORS attribute names — source data, never modified, reformatted or normalised (R1).
 *
 * Real attribute names carry spaces, dots, slashes, parentheses and umlauts (`REQ. Priorität`,
 * `RFD/RFW`, `DXL for Out-links (AKA)`), so an attribute name is **never** a Kotlin identifier, a
 * CSS class or a URL segment — only a map key and a display label. The names declared here are
 * the handful the backend has to reason about by name; every other attribute a module carries is
 * discovered at runtime and never appears in code.
 */
public object DoorsAttr {
    /**
     * DOORS's own object identifier. Module-local, so it is never a key (R6) — [DoorsProp.ID] is.
     * It is shown, as the review table's ID column.
     */
    public const val ID: String = "id"

    /**
     * The outline number. Displayed, and part of a heading's Description — but **never** sorted
     * on: it does not sort correctly as a string, which is the entire reason [DoorsProp.SORT_KEY]
     * exists (R3).
     */
    public const val OBJECT_NUMBER: String = "objectNumber"

    /** Outline depth. Structural, and excluded from attribute discovery alongside the two above. */
    public const val OBJECT_LEVEL: String = "objectLevel"

    /** The body text of anything that is not a heading. Half of the Description column. */
    public const val OBJECT_TEXT: String = "Object Text"

    /** The title of a heading. The other half of the Description column. */
    public const val OBJECT_HEADING: String = "Object Heading"

    /** The attribute [DoorsLabel] type labels are derived from. Blanked by a sanitised export. */
    public const val OBJECT_TYPE: String = "Object Type"

    /**
     * Structural attributes: present on every object, meaningless as a review column, and
     * therefore filtered out of runtime attribute discovery along with the `__` namespace.
     */
    public val structural: Set<String> = setOf(ID, OBJECT_NUMBER, OBJECT_LEVEL)

    /**
     * The two attributes the review table's Description column is built from (REQ_REVIEW.md §5).
     *
     * They are still discovered and still listed in the settings dialog, as checked-and-disabled
     * rows so a reviewer can see *why* they cannot be turned off. Offering them as optional
     * columns would let a module show the same sentence twice.
     */
    public val description: Set<String> = setOf(OBJECT_HEADING, OBJECT_TEXT)
}

/** `:DOORSModule` property names, for the settings dialog's first tab. Source data, so displayed. */
public object DoorsModuleAttr {
    public const val DESCRIPTION: String = "description"
    public const val FULL_PATH: String = "moduleFullPath"
    public const val PREFIX: String = "prefix"
    public const val CREATED_BY: String = "created_By"
    public const val CREATED_ON: String = "created_On"
    public const val LAST_MODIFIED_BY: String = "last_Modified_By"
    public const val LAST_MODIFIED_ON: String = "last_Modified_On"

    /** A **single** leading underscore — source data, and displayed, unlike a `__` name (R5). */
    public const val MODULE_TYPE: String = "_ModuleType"

    public const val WORD_DOC_BASELINE: String = "wordDocBaseline"
    public const val WORD_DOC_CAPTION_LEVEL: String = "wordDocCaptionLevel"
    public const val WORD_DOC_ISSUE: String = "wordDocIssue"
    public const val WORD_DOC_NUMBER: String = "wordDocNumber"
    public const val WORD_DOC_TITLE: String = "wordDocTitle"
}

/**
 * Tier-1 properties the DOORS importer derives that **no other source has**.
 *
 * The shared ones — `__id`, `__name`, `__sortKey`, `__moduleUrl` — are not repeated here. DOORS
 * code reads them from `com.sec.domain.Prop` like every other source does, so `__id` keeps exactly
 * one spelling in the backend and a second name for it never comes into existence.
 */
public object DoorsProp {
    /**
     * Exported table geometry — a **cross-check** on the reconstructed geometry, never its source
     * (`docs/DOORS_TABLES.md` §2.1). The export has a corrupt-key defect and the importer omits an
     * index it cannot parse, so `null` here is normal rather than a fault.
     */
    public const val TABLE_ROW_INDEX: String = "__tableRowIndex"
    public const val TABLE_COLUMN_INDEX: String = "__tableColumnIndex"

    /** The owning table, as a fallback when the `__child` walk finds nothing mid-re-import. */
    public const val TABLE_URL: String = "__tableURL"

    /**
     * The run stamp (ADR 0012): written on every object, `__child` and `refersTo` an import run
     * confirms. Anything still carrying an older stamp when the run finishes is something the
     * export did not mention — the comparison the seven-statement reconciliation runs itself,
     * with no parameter that grows with the module.
     */
    public const val IMPORTED_AT: String = "__importedAt"

    /**
     * The module URL a `refersTo` edge's *source* module asserted it from — set once, `ON CREATE`,
     * because DOORS's own outgoing-link list is what makes an edge authoritative for the module
     * that owns it, and a later import of the *target's* module must not overwrite that.
     */
    public const val SOURCE_MODULE_URL: String = "__sourceModuleUrl"

    /**
     * A `:__UNDEFINED` placeholder's Absolute Number, from the link that created it. Deliberately
     * **not** `__`-prefixed, matching the Python importer this is ported from — it predates R1's
     * closed reading of the namespace and is not reopened here; see ADR 0019 §7.
     */
    public const val ABSOLUTE_NUMBER: String = "absoluteNumber"
}

/** The DOORS traceability relationship. Un-prefixed because DOORS actually asserts it (R3). */
public object DoorsRel {
    /** Untyped in DOORS, and untyped here. Rendered as **References**, and in the Breakdown tab
     *  as *refines ‹parent id›* — a display convention of that one tab (R5). */
    public const val REFERS_TO: String = "refersTo"
}

/**
 * Type labels the importer derives from [DoorsAttr.OBJECT_TYPE], plus the two structural labels
 * every DOORS node carries.
 *
 * These cross the wire as `labels: string[]`, which is the one place raw label strings are
 * allowed out — as a *state channel*, never as display text. The wording is in `domain/Aliases.kt`.
 */
public object DoorsLabel {
    /** Carried by every object of a module, including the module node itself. */
    public const val OBJECT: String = "DOORSObject"

    /** The module node. Also carries [OBJECT], so queries scoped to objects exclude it. */
    public const val MODULE: String = "DOORSModule"

    public const val REQUIREMENT: String = "DOORSRequirement"
    public const val HEADING: String = "DOORSHeading"
    public const val INFORMATION: String = "DOORSInformation"
    public const val APP_MATRIX: String = "DOORSAppMatrix"
    public const val APP_MATRIX_HEADING: String = "DOORSAppMatrixHeading"

    /**
     * `Object Type` was absent or empty. A sanitised export blanks every user attribute, so on the
     * fixtures people share *everything* imports as this — which is why no view may exclude it.
     */
    public const val TBD: String = "DOORSTBD"

    public const val TABLE: String = "DOORSTable"
    public const val TABLE_ROW: String = "DOORSTableRow"
    public const val TABLE_CELL: String = "DOORSTableCell"

    /**
     * The three roles of an embedded table. DOORS does not type these, so they arrive as [TBD]
     * with `Object Type` reading the literal string "TBD" — a fact two separate checks in
     * [DoorsChecks] have to excuse.
     */
    public val tableStructure: Set<String> = setOf(TABLE, TABLE_ROW, TABLE_CELL)

    /** Every label this importer writes. Used by the Cypher guard test. */
    public val all: Set<String> = setOf(
        OBJECT, MODULE, REQUIREMENT, HEADING, INFORMATION, APP_MATRIX, APP_MATRIX_HEADING,
        TBD, TABLE, TABLE_ROW, TABLE_CELL,
    )
}
