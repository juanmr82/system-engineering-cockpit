package com.sec.source.doors

/**
 * The consistency checks and the type vocabulary they run against, in **one** place.
 *
 * These rules were private to [ReviewProjection] until the Statistics view needed the same
 * numbers (`docs/features/requirements-statistics.md` §3.2). They were deliberately **not**
 * re-expressed as Cypher aggregates there, even though `o[k]` accepts a variable key and would be
 * faster: the same module could then report 41 missing mandatory values in the Req review table
 * and 43 in the Statistics view, and nobody would ever be able to say which was right. A summary
 * that disagrees with the table it summarises is worse than no summary.
 *
 * DOORS label names appear here, so this belongs in the DOORS source package and nowhere else
 * (CLAUDE.md §1). The two genuinely source-agnostic pieces live outside it: `domain/Cycles.kt`
 * for loop detection and `domain/TextMarkers.kt` for the TBD/TBC scan.
 */
public object DoorsChecks {

    /**
     * Labels that make an object "requirement-like" for the review view's requirements-only
     * filter (REQ_REVIEW.md §11 O4) and for the Statistics view's requirement count. Table
     * structure, headings and information objects are context, not requirements — the same scope
     * the mandatory-attribute check uses (attribute-policy-checks.md §1), so every view agrees on
     * what a requirement is.
     *
     * DOORSTBD counts: a sanitised export blanks `Object Type`, so every object imports as TBD and
     * excluding it would empty the table on exactly the fixtures people share (CLAUDE.md §10).
     */
    public val requirementLikeTypes: Set<String> = setOf("DOORSRequirement", "DOORSTBD")

    public val structuralTypes: Set<String> = setOf("DOORSTable", "DOORSTableRow", "DOORSTableCell")

    public const val TBD_LABEL: String = "DOORSTBD"

    public const val UNRESOLVED_LABEL: String = "__UNDEFINED"

    /**
     * Table structure is exempt from the fixed TBD check because DOORS genuinely does not type the
     * cells and rows of an embedded table, and `:__UNDEFINED` is exempt because a placeholder for
     * an object no import has reached has no `Object Type` to be wrong — reporting either would be
     * reporting on the importer's own bookkeeping.
     */
    private val tbdCheckExclusions: Set<String> = structuralTypes + UNRESOLVED_LABEL

    /**
     * The wording a reviewer reads. "Object Type" is the DOORS attribute the label came from and
     * "TBD" is that label's alias, so this sentence is displayable under R5 — no `DOORSTBD` and no
     * `__`-prefixed name reaches it.
     */
    public const val TBD_ISSUE: String = "Object Type shall not be TBD"

    /** One mandatory-attribute rule: which attribute, and which objects it applies to. */
    public data class MandatoryPolicy(
        val attributeName: String,
        val appliesToLabels: Set<String>,
    )

    public fun isRequirementLike(labels: Collection<String>): Boolean =
        labels.any { it in requirementLikeTypes } && labels.none { it in structuralTypes }

    public fun isStructural(labels: Collection<String>): Boolean =
        labels.any { it in structuralTypes }

    public fun isPlaceholder(labels: Collection<String>): Boolean =
        labels.contains(UNRESOLVED_LABEL)

    /**
     * The absent-or-blank rule, in one place because three checks depend on agreeing about it.
     *
     * DOORS `""` means "the attribute exists and is empty", which the review table renders as an
     * empty cell rather than as absent (CLAUDE.md §11) — but for every check the two are equally a
     * violation, and the distinction is not surfaced.
     *
     * A non-string property is present and typed — the importer coerces a handful to integers.
     * Comparing one to `""` would quietly evaluate false rather than throw, so it is answered
     * explicitly instead of by accident.
     */
    public fun isEmptyValue(value: Any?): Boolean =
        when (value) {
            null -> true
            is String -> value.isBlank()
            else -> false
        }

    /**
     * Everything the consistency checks find wrong with one object (`REQ_REVIEW.md` §5.3).
     *
     * Fixed rules first, then the configured ones: a typed object with an unfilled attribute is a
     * different conversation from an object that was never classified at all, and the second is
     * the more fundamental problem.
     */
    public fun issuesFor(
        policies: List<MandatoryPolicy>,
        labels: List<String>,
        props: Map<String, Any?>,
    ): List<String> = buildList {
        if (labels.contains(TBD_LABEL) && labels.none { it in tbdCheckExclusions }) {
            add(TBD_ISSUE)
        }
        addAll(missingMandatory(policies, labels, props))
    }

    /**
     * The mandatory attributes this object should carry a value for and does not.
     *
     * Scope comes from the policy's own `appliesToLabels`, never from a default living here, and
     * table structure is excluded whatever the policy says — a table cell is a fragment of a
     * requirement's layout, not a requirement (`attribute-policy-checks.md` §1).
     */
    public fun missingMandatory(
        policies: List<MandatoryPolicy>,
        labels: List<String>,
        props: Map<String, Any?>,
    ): List<String> {
        if (policies.isEmpty() || isStructural(labels)) {
            return emptyList()
        }
        val labelSet = labels.toSet()
        return policies
            .filter { policy -> policy.appliesToLabels.any { it in labelSet } }
            .map { it.attributeName }
            .filter { name -> isEmptyValue(props[name]) }
    }

    /**
     * The verification attributes this object should carry a value for and does not
     * (`REQ_REVIEW.md` §9.2, and requirements-statistics.md §3.5).
     *
     * Same absent-or-blank rule as [missingMandatory], so the two completeness numbers are
     * comparable. A module with no verification attribute configured yields an empty list here —
     * the caller must not read that as "clean", which is why the Statistics view reports "not
     * configured" as its own state rather than as a zero.
     *
     * Scoped to **requirements**, which is stricter than [missingMandatory]'s structural-only
     * exemption and is deliberate: a mandatory-attribute policy carries its own `appliesToLabels`
     * and can legitimately be aimed at headings, but verification is a commitment about how a
     * requirement will be shown to be met. A heading has nothing to verify, and counting one as
     * unverified would put every module's section titles into the finding.
     */
    public fun missingVerification(
        verificationAttributes: Collection<String>,
        labels: List<String>,
        props: Map<String, Any?>,
    ): List<String> {
        if (verificationAttributes.isEmpty() || !isRequirementLike(labels)) {
            return emptyList()
        }
        return verificationAttributes.filter { name -> isEmptyValue(props[name]) }
    }
}
