package com.sec.source.doors

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `__sortKey` is Tier-1, so R1 requires the Kotlin importer and the Python one to derive the
 * **same string** for the same input — "delete it, re-run the import over the same file, and you
 * get byte-identical results" has to hold whichever importer ran.
 *
 * Nothing enforced that before: the two implementations were separately written and separately
 * tested, and they drifted the moment one was fixed. This reads a table the Python side produced
 * from every distinct objectNumber in the committed real exports and asserts Kotlin agrees.
 *
 * Skips when the table is absent, so it never breaks a machine without Python — regenerate with
 * the snippet in ADR 0022.
 */
class CrossLanguageSortKeyCheck {

    @Test
    fun `kotlin and python derive the same sort key for every real object number`() {
        val table = Path.of("..", "importers", "tests", "fixtures", "sort_key_table.tsv")
        if (!Files.exists(table)) return

        val mismatches = Files.readAllLines(table)
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val (number, expected) = line.split('\t', limit = 2)
                val actual = DoorsDerivations.sortKey(number)
                if (actual == expected) null else "$number: python=$expected kotlin=$actual"
            }

        assertEquals(emptyList(), mismatches, "the two importers disagree on __sortKey")
    }
}
