package com.sec.domain

/**
 * Open-point markers left in requirement prose — "TBD", "TBC".
 *
 * This is a **text heuristic over source data**, and the view that uses it says so in words
 * (`docs/features/requirements-statistics.md` §3.3). It is not a graph fact and it is not the
 * `DOORSTBD` label, which is a different thing entirely: that label means the export never carried
 * an `Object Type`, and it stays the Req review Issues column's fixed check, per row, where it is
 * actionable.
 *
 * Source-agnostic: a Windchill document or a Cameo element carries the same markers in the same
 * prose, so this lives in `domain/` rather than in a source package.
 */
public object TextMarkers {

    /**
     * `TBD`, `TBC`, and their plurals, case-insensitive, never inside a word.
     *
     * The letter boundaries are what stop `ATBD` and `TBDX` matching. `\b` would not do: it treats
     * a digit as a word character, so `TBD2` would fail to match where `TBD 2` matched, and the
     * distinction is meaningless in requirement prose.
     *
     * It will match the sentence "no TBD items remain". That is accepted, and stated in the band:
     * the alternative is a curated marker list that is right for this project and wrong for the
     * next one.
     */
    private val OPEN_POINT = Regex("(?<!\\p{L})(TBD|TBC)s?(?!\\p{L})", RegexOption.IGNORE_CASE)

    public fun carriesOpenPoint(text: String): Boolean = OPEN_POINT.containsMatchIn(text)

    /**
     * The attribute names on one item whose value carries an open-point marker.
     *
     * Namespace-filtered, so `__name` cannot contribute a second count for text already scanned
     * under its real attribute — and so no `__`-prefixed name can reach the by-attribute ranking
     * the view renders (R5).
     *
     * Only string values are scanned; the importer coerces a handful of attributes to integers and
     * a number cannot carry a marker.
     */
    public fun attributesCarrying(props: Map<String, Any?>): List<String> =
        props.entries
            .filter { (key, value) ->
                !key.startsWith("__") && value is String && carriesOpenPoint(value)
            }
            .map { it.key }
            .sorted()
}
