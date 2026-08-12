package com.sec.source.jira.mapping

import com.sec.source.jira.jiraJson
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The storage rules of spec §7.1 and §7.2, one case per shape.
 *
 * §16.1 asks for one case per distinct `(schema.type, schema.items, observed JSON shape)` triple —
 * there are exactly **28** in the committed export, confirmed by counting them. They do not need 28
 * separate tests here, and that is the point rather than a shortcut: the classifier **never sees a
 * declared type**, so the 28 triples collapse to seven shapes. `ClassifierAgainstTheExportTest`
 * closes the loop by driving all 28 through the real fixture and asserting the collapse is total.
 */
class ValueClassifierTest {

    // -- skipped ------------------------------------------------------------------------------

    /** 43 902 of the export's 50 800 keys. This one rule is most of the storage design. */
    @Test
    fun `null is skipped`() {
        assertIs<StoredValue.Skip>(classify("null"))
    }

    @Test
    fun `an empty array is skipped`() {
        assertIs<StoredValue.Skip>(classify("[]"))
    }

    // -- scalars -------------------------------------------------------------------------------

    @Test
    fun `a string is stored verbatim`() {
        assertEquals(StoredValue.Scalar("Something"), classify("\"Something\""))
    }

    /**
     * A date is a **string**, exactly as JIRA sent it, offset included. Converting to a Neo4j
     * temporal would violate R1 and lose the original offset; ISO-8601 with a fixed-width offset
     * still sorts correctly as text, and the display layer parses on demand (spec §7.2).
     */
    @Test
    fun `a datetime keeps its original offset as text`() {
        assertEquals(
            StoredValue.Scalar("2026-08-09T11:38:00.697+0200"),
            classify("\"2026-08-09T11:38:00.697+0200\""),
        )
    }

    @Test
    fun `an integer stays an integer, and a decimal stays a decimal`() {
        assertEquals(StoredValue.Scalar(-1L), classify("-1"))
        assertEquals(StoredValue.Scalar(1.5), classify("1.5"))
    }

    @Test
    fun `a boolean is a boolean, not the string true`() {
        assertEquals(StoredValue.Scalar(true), classify("true"))
    }

    /**
     * `""` is **stored**, which departs from the letter of §7.1 and is argued in the classifier.
     *
     * In short: the spec's own exception — "an emptied field must be set to `""`, not removed" —
     * is unreachable if `""` is skipped, because phase 3 removes every key absent from
     * `presentKeys`. Storing it makes the exception the rule, and matches what `""` has always
     * meant on the DOORS side: exists, and is empty.
     */
    @Test
    fun `an empty string is stored, because an emptied field is information`() {
        assertEquals(StoredValue.Scalar(""), classify("\"\""))
    }

    // -- lists ----------------------------------------------------------------------------------

    @Test
    fun `an array of strings is a list, in order`() {
        val stored = assertIs<StoredValue.ListOfScalars>(classify("""["Something","M5","M5.1"]"""))

        assertEquals(listOf("Something", "M5", "M5.1"), stored.values)
    }

    @Test
    fun `an array of integers is a list of integers`() {
        assertEquals(listOf(1L, 2L, 3L), assertIs<StoredValue.ListOfScalars>(classify("[1,2,3]")).values)
    }

    /**
     * Neo4j stores a list property as a **typed** array, so `[1, 2.5]` cannot hold both an integer
     * and a decimal. Promoting is lossless; letting the driver see it is a failure mid-batch.
     */
    @Test
    fun `a mixed integer and decimal array is promoted to decimals rather than failing`() {
        assertEquals(listOf(1.0, 2.5), assertIs<StoredValue.ListOfScalars>(classify("[1,2.5]")).values)
    }

    /** Not storable as a list at all, so the array is kept whole rather than half-lost. */
    @Test
    fun `an array mixing strings and numbers becomes JSON text`() {
        assertIs<StoredValue.JsonText>(classify("""["a",1]"""))
    }

    /**
     * Dropping the null would silently shorten the array, and for a positional list a length change
     * is a change of meaning rather than a tidy-up.
     */
    @Test
    fun `an array containing a null becomes JSON text rather than a shorter list`() {
        val stored = assertIs<StoredValue.JsonText>(classify("""["a",null,"b"]"""))

        assertTrue(stored.json.contains("null"), stored.json)
    }

    // -- complex --------------------------------------------------------------------------------

    @Test
    fun `an object becomes its own JSON text, losing nothing`() {
        val raw = """{"self":"https://jira/x","value":"WSS","id":"38303","disabled":false}"""
        val stored = assertIs<StoredValue.JsonText>(classify(raw))

        // Round-trips: the API layer parses this back when the frontend asks for a whole issue.
        assertEquals(jiraJson.parseToJsonElement(raw), jiraJson.parseToJsonElement(stored.json))
    }

    @Test
    fun `an array of objects becomes the JSON text of the array`() {
        val raw = """[{"name":"a","checked":true},{"name":"b","checked":false}]"""
        val stored = assertIs<StoredValue.JsonText>(classify(raw))

        assertEquals(jiraJson.parseToJsonElement(raw), jiraJson.parseToJsonElement(stored.json))
    }

    // -- the pathological cases §16.1 names ------------------------------------------------------

    /**
     * `any`-typed fields are the argument for shape-driven classification in one test: the same
     * declared type holds a string 216 times and an empty array 48 times in the export, and only
     * the value says which.
     */
    @Test
    fun `an any-typed field is classified by what it holds, not by what it claims`() {
        assertEquals(StoredValue.Scalar("0|0hzzd4:"), classify("\"0|0hzzd4:\""))
        assertIs<StoredValue.Skip>(classify("[]"))
    }

    /**
     * The classifier takes no catalogue at all — it is a function of one `JsonElement`. That is the
     * strongest available statement that a field with no definition cannot break it, and it is why
     * §16.1's "must not crash" case needs no special handling anywhere.
     */
    @Test
    fun `a field the catalogue has never heard of classifies like any other`() {
        assertEquals(StoredValue.Scalar("value"), classify("\"value\""))
    }

    @Test
    fun `deeply nested structures survive as text`() {
        val raw = """{"a":{"b":{"c":[{"d":[1,2]}]}}}"""
        assertEquals(
            jiraJson.parseToJsonElement(raw),
            jiraJson.parseToJsonElement(assertIs<StoredValue.JsonText>(classify(raw)).json),
        )
    }

    private fun classify(json: String): StoredValue =
        ValueClassifier.classify(jiraJson.parseToJsonElement(json) as JsonElement)
}
