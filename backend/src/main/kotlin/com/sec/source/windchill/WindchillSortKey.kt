package com.sec.source.windchill

/**
 * Windchill's implementation of the one Tier-1 ordering contract (R3).
 *
 * `__sortKey` has the same meaning for every source and a different derivation in each: **a plain
 * string sort on it reproduces the order the view wants to show.** DOORS zero-pads the segments of
 * an outline number; this does two things DOORS does not have to.
 *
 * ## What the order has to be
 *
 * ```
 *   XXX-ADSF-RP-000014092   03 [1]      ← same Number, newest revision first
 *   XXX-ADSF-RP-000014092   02 [2]
 *   XXX-ADSF-RP-000014092   01 [2]
 *   XXX-ADSF-RP-000823676   01 [2]      ← next Number
 * ```
 *
 * Two rules, and they point in opposite directions. Documents are grouped by `Number`, **ascending**
 * — every version of one document adjacent, which is what makes the Documents view able to draw a
 * group header at all. Versions inside a group are **descending**, because the current revision is
 * what a reader wants first and it belongs directly under the header.
 *
 * ## How a descending segment is made to sort ascending
 *
 * By complementing it. Every run of digits in the version string is read as a number and stored as
 * `999999999 - n`, zero-padded, so a **larger** version produces a **smaller** key. `02 [2]` becomes
 * `999999997…` and `01 [2]` becomes `999999998…`, and a plain ascending sort puts `02` first.
 *
 * Three runs are read, which covers every Windchill version shape seen — `01 [2]`, `A.2`, `1.2.3` —
 * and an absent run is treated as zero, so it complements to the largest key and sorts last. That is
 * the right answer: a version that says less is the older one by convention.
 *
 * ## The tail, and why it is there
 *
 * The raw version string is appended after the numeric key. Letters are not complementable — there
 * is no arithmetic that turns `B` into something sorting before `A` without inventing an alphabet —
 * so two versions differing **only** in letters (`A.2` and `B.2`) produce the same numeric key. The
 * tail breaks that tie deterministically rather than leaving two rows in whatever order the database
 * returned them. It is stable, not correct, and [isReadable] is what lets the importer say so.
 */
public object WindchillSortKey {

    /**
     * Separates the number from the version part, and it is **U+0001 rather than a printable
     * character** for a reason that is easy to get wrong twice.
     *
     * A separator only does its job if it sorts below every character a `Number` can contain.
     * `|` looks safe because it sorts after every letter and digit — and that is the wrong
     * direction: with `|`, `ABC-1` sorts *before* `ABC`, because `-` (U+002D) is below `|` (U+007C)
     * and the comparison reaches the separator only on the shorter string. Groups stayed adjacent,
     * which is what made it look right, and the groups themselves came out in an order no plain
     * sort of `Number` would produce.
     *
     * U+0001 is below every printable character there is, so the number segment is always compared
     * as a whole. It never reaches a user — `__sortKey` is internal (R5) — and it cannot occur in
     * Windchill data.
     */
    private const val SEPARATOR = '\u0001'

    /** How many digit runs of a version participate in the order. */
    private const val SEGMENTS = 3

    /** The complement base. Nine digits covers any revision or iteration Windchill will mint. */
    private const val MAX = 999_999_999L

    /** Fixed width, so the segments concatenate into one comparable string. */
    private const val WIDTH = 9

    private val DIGITS = Regex("\\d+")

    /** The key for one document: its number ascending, then its version descending. */
    public fun derive(number: String, version: String): String =
        number + SEPARATOR + descendingVersion(version)

    /**
     * Whether this code can order [version] by anything but a string comparison.
     *
     * False for a version carrying no digits at all, which is the only case where the numeric key is
     * entirely uninformative and the tie-breaking tail is doing all the work. The importer warns on
     * it rather than failing: an unorderable version is still a document.
     */
    public fun isReadable(version: String): Boolean = DIGITS.containsMatchIn(version)

    private fun descendingVersion(version: String): String {
        val runs = DIGITS.findAll(version)
            .map { it.value.toLongOrNull() ?: MAX }
            .take(SEGMENTS)
            .toList()

        return buildString {
            repeat(SEGMENTS) { index ->
                val value = runs.getOrElse(index) { 0L }.coerceIn(0L, MAX)
                append((MAX - value).toString().padStart(WIDTH, '0'))
            }
            // Deterministic tie-break for versions that differ only in letters. See the class note.
            append(SEPARATOR).append(version)
        }
    }
}
