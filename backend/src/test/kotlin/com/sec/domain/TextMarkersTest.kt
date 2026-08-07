package com.sec.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// docs/features/requirements-statistics.md §3.3. The metric is a text heuristic and these tests
// pin exactly how far the heuristic reaches, so a later "improvement" to the regex has to argue
// with a case rather than with a comment.
class TextMarkersTest {

    @Test
    fun `finds the markers in ordinary requirement prose`() {
        assertTrue(TextMarkers.carriesOpenPoint("The mass shall be TBD kg."))
        assertTrue(TextMarkers.carriesOpenPoint("Interface voltage TBC."))
    }

    @Test
    fun `is case insensitive`() {
        assertTrue(TextMarkers.carriesOpenPoint("value tbd"))
        assertTrue(TextMarkers.carriesOpenPoint("value Tbc"))
    }

    @Test
    fun `matches a plural`() {
        assertTrue(TextMarkers.carriesOpenPoint("Two TBDs remain in this section."))
    }

    @Test
    fun `does not match inside a word`() {
        assertFalse(TextMarkers.carriesOpenPoint("ATBD"))
        assertFalse(TextMarkers.carriesOpenPoint("TBDX"))
        assertFalse(TextMarkers.carriesOpenPoint("contributed"))
    }

    @Test
    fun `a digit is not a letter, so a marker followed by one still counts`() {
        // Why the boundary is \p{L} and not \b: \b treats a digit as a word character, so `TBD2`
        // would fail where `TBD 2` matched, and that distinction is meaningless in prose.
        assertTrue(TextMarkers.carriesOpenPoint("See TBD2 in the annex."))
    }

    @Test
    fun `punctuation around the marker does not hide it`() {
        assertTrue(TextMarkers.carriesOpenPoint("Mass: (TBD)"))
        assertTrue(TextMarkers.carriesOpenPoint("Rate = TBC, to be confirmed"))
    }

    @Test
    fun `the known false positive is a false positive, and that is accepted`() {
        // §3.3 states this in the band itself rather than pretending it does not happen.
        assertTrue(TextMarkers.carriesOpenPoint("No TBD items remain."))
    }

    @Test
    fun `reports which attributes carry a marker, namespace filtered`() {
        val props = mapOf(
            "Object Text" to "Mass shall be TBD kg",
            "Rationale" to "Confirmed",
            "REQ. Priorität" to "TBC",
            "__name" to "TBD",
            "objectLevel" to 2,
        )

        // Sorted, and `__name` is filtered out — no internal name may reach the by-attribute
        // ranking the view renders (R5).
        assertEquals(listOf("Object Text", "REQ. Priorität"), TextMarkers.attributesCarrying(props))
    }

    @Test
    fun `a non string value cannot carry a marker`() {
        assertEquals(emptyList(), TextMarkers.attributesCarrying(mapOf("count" to 42)))
    }

    @Test
    fun `an empty value is not an open point`() {
        // DOORS "" means the attribute exists and is empty, which is the mandatory check's
        // business, not this one's.
        assertEquals(emptyList(), TextMarkers.attributesCarrying(mapOf("Object Text" to "")))
    }
}
