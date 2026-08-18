package com.sec.source.doors

import com.sec.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The parser against three **real** DOORS module exports, sanitised — a Customer module at L0, a
 * System module at L1 and a SubSystem module at L2, from one decomposition, linked to each other.
 *
 * `DoorsExportParserTest` beside this file pins one rule each against hand-written exports, which
 * is the right shape for a rule. This one exists for what a hand-written export cannot contain:
 * attribute names with spaces, hyphens, parentheses and version suffixes
 * (`AR-BS Compliance Comment (AKA_V2.1)`), 78-odd attributes per object, real outline numbering
 * several levels deep, real table geometry, and `refersTo` links that leave the module. Those are
 * precisely the inputs CLAUDE.md §11 warns about — "never use a DOORS attribute name as an object
 * key path, CSS class, or URL segment" — and the only place the codebase meets them for real.
 *
 * Nothing here asserts an exact object count. The fixtures are real files that may be re-exported,
 * and a test that has to be edited whenever the data is refreshed is a test that gets deleted. The
 * assertions are invariants of the derivation instead: every one of them would have caught a real
 * defect, and none of them depends on which export it reads.
 */
class DoorsRealExportTest {

    private fun exports(): List<Pair<String, DoorsExport>> = Fixtures.DOORS_EXPORTS.map { name ->
        name to DoorsExportParser.parse(Fixtures.bytes(name)).getOrThrow()
    }

    @Test
    fun `every committed export parses into a module with objects`() {
        exports().forEach { (name, export) ->
            assertTrue(export.moduleId.startsWith("doors://"), "$name: module id is not a DOORS URL")
            assertTrue(export.moduleName.isNotBlank(), "$name: no module name")
            assertEquals("current", export.moduleVersion, "$name")
            assertTrue(export.objects.size > 500, "$name: only ${export.objects.size} objects")
        }
    }

    /**
     * R3's contract, on real data: **a plain string sort on `__sortKey` reproduces the source
     * tool's own display order.** The export arrives in that order, so the check is that sorting
     * by the derived key does not move anything — which `objectNumber` itself would fail, since
     * `10` sorts before `9` as a string. That is the whole reason `__sortKey` exists.
     *
     * This caught a real defect (ADR 0022): the key used to keep both `.` and `-` as separators,
     * and `-` (0x2D) sorts before `.` (0x2E), so `6.2.1-1` came out ahead of `6.2.1.0-7`. One
     * inversion in 2 446 objects — invisible to any hand-written fixture, because it needs two
     * numbers of different depth under one parent to appear at all.
     */
    @Test
    fun `a string sort on the derived sort key reproduces document order exactly`() {
        val inversions = exports().flatMap { (name, export) ->
            val keys = export.objects.map { obj ->
                obj.props["__sortKey"] as? String ?: error("$name: an object has no __sortKey")
            }
            val numbers = export.objects.map { it.objectNumber }
            (1 until keys.size)
                .filter { keys[it] < keys[it - 1] }
                .map { "$name: ${numbers[it - 1]} -> ${numbers[it]}" }
        }

        assertEquals(emptyList(), inversions, "__sortKey does not reproduce document order")
    }

    /**
     * The other half of the same contract: normalising `.` and `-` to one separator must not make
     * two genuinely different `objectNumber`s collide on one key, or the sort becomes arbitrary
     * between them.
     */
    @Test
    fun `no two different object numbers share a sort key`() {
        exports().forEach { (name, export) ->
            val byKey = export.objects.groupBy { it.props["__sortKey"] as? String }
            val collisions = byKey.filterValues { group -> group.map { it.objectNumber }.distinct().size > 1 }
            assertTrue(collisions.isEmpty(), "$name: distinct object numbers share a __sortKey: ${collisions.keys}")
        }
    }

    /** R1/R6: identity is per object and the graph keys on it, so a duplicate would silently merge two. */
    @Test
    fun `object urls are unique within a module`() {
        exports().forEach { (name, export) ->
            val urls = export.objects.map { it.objectUrl }
            assertEquals(urls.size, urls.toSet().size, "$name: duplicate __objectUrl in one export")
        }
    }

    /** Every object is an `:SEItem` — the join point every future source hangs off (CLAUDE.md §1). */
    @Test
    fun `every object carries SEItem and the three identity properties`() {
        exports().forEach { (name, export) ->
            export.objects.forEach { obj ->
                assertTrue("SEItem" in obj.labels, "$name: ${obj.objectUrl} is not an SEItem")
                assertNotNull(obj.props["__name"], "$name: ${obj.objectUrl} has no __name")
                assertEquals("current", obj.props["__version"], "$name: ${obj.objectUrl}")
            }
        }
    }

    /**
     * The real reason these three files are committed together rather than one of them alone: they
     * link to each other. A cross-module `refersTo` is what the Breakdown tab and the dependency
     * graph are made of, and it is the one thing a single-module fixture can never exercise.
     */
    @Test
    fun `the three modules link to each other, not only within themselves`() {
        val parsed = exports()
        val ownUrls = parsed.associate { (name, export) -> name to export.objects.map { it.objectUrl }.toSet() }

        val crossModule = parsed.sumOf { (name, export) ->
            val own = ownUrls.getValue(name)
            export.objects.sumOf { obj ->
                obj.outputLinks.count { it.reqDocumentUrl !in own && it.reqDocumentUrl != export.moduleId }
            }.toLong()
        }

        assertTrue(crossModule > 0, "no link leaves its own module; the fixture set is three unrelated files")
    }

    /**
     * The attribute names CLAUDE.md §11 is about. If a future change ever normalises, trims or
     * slugifies a source attribute name, this fails — which is the point, because R1 says source
     * data is "never modified, reformatted, or normalised by the application".
     */
    @Test
    fun `source attribute names are carried through verbatim, punctuation and all`() {
        val names = exports()
            .flatMap { (_, export) -> export.objects }
            .flatMap { it.props.keys }
            .filterNot { it.startsWith("__") }
            .toSet()

        assertTrue(names.any { it.contains(' ') }, "no attribute name with a space survived")
        assertTrue(names.any { it.contains('-') }, "no attribute name with a hyphen survived")
        assertTrue(names.any { it.contains('(') }, "no attribute name with a parenthesis survived")
        assertTrue(names.size > 50, "only ${names.size} distinct attribute names across three real modules")
    }

    /**
     * **An attribute exported as `""` reaches the graph as `""`.**
     *
     * `""` from DOORS means "this attribute exists on this object and has no value", which is a
     * different fact from "this object does not have this attribute" (CLAUDE.md §11) — and the
     * alias map renders the first as *Empty* rather than as a blank cell. The parser used to drop
     * them, which made the two states indistinguishable and that *Empty* state unreachable for
     * DOORS data. ADR 0022.
     *
     * The derived `__` properties are still dropped when empty, and that distinction is the point:
     * an object with no `__tableID` is not in a table, which is an absence, not an empty value.
     */
    @Test
    fun `an attribute exported as an empty string is stored as empty, not dropped`() {
        val rawHasEmpty = Fixtures.text(Fixtures.DOORS_EXPORTS.first()).contains("\"Object Short Text\":\"\"")
        assertTrue(rawHasEmpty, "the fixture no longer carries an empty attribute; this test is vacuous")

        val stored = exports().flatMap { (_, export) -> export.objects }
        assertTrue(
            stored.any { obj -> obj.props.any { (k, v) -> !k.startsWith("__") && v == "" } },
            "no empty source attribute survived parsing; they are being dropped again",
        )
        assertTrue(
            stored.none { obj -> obj.props.any { (k, v) -> k.startsWith("__") && v == "" } },
            "an empty derived __ property was stored; those are absences, not empty values",
        )
    }
}
