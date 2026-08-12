package com.sec.source.jira

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The JQL builder (spec §8).
 *
 * Small enough to read in one screen and the only place user-supplied text becomes query syntax,
 * which is the whole reason it has its own file and its own tests.
 */
class JiraJqlTest {

    private val snapshot: Instant = Instant.parse("2026-08-11T12:32:45Z")
    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")

    @Test
    fun `the query names the projects, bounds the snapshot and orders by key`() {
        val jql = JiraJql.build(listOf("ProjectCRPT", "ProjectITSEC"), snapshot, berlin).getOrThrow()

        assertEquals(
            """project in ("ProjectCRPT","ProjectITSEC") AND created <= "2026/08/11 14:32" ORDER BY key ASC""",
            jql,
        )
    }

    /**
     * JIRA's literal format, which is not ISO-8601. `Instant.toString()` would put a `T` in the
     * middle and JIRA answers 400 with a message about JQL syntax — an error that sends you
     * looking at the clauses rather than at the timestamp.
     */
    @Test
    fun `the created bound is formatted the way JQL reads it, not ISO-8601`() {
        val jql = JiraJql.build(listOf("A"), snapshot, berlin).getOrThrow()

        assertTrue(jql.contains("""created <= "2026/08/11 14:32""""), jql)
        assertTrue(!jql.contains("T"), "an ISO-8601 separator reached the JQL: $jql")
    }

    /**
     * The bound is compared against JIRA's clock, so it is formatted in JIRA's zone. Getting this
     * from the JVM instead moves the snapshot boundary by the offset between the two — silently,
     * and only for deployments where they differ, which is the worst way to find a bug.
     */
    @Test
    fun `the bound is rendered in the server's zone, not the JVM's`() {
        val berlinJql = JiraJql.build(listOf("A"), snapshot, berlin).getOrThrow()
        val utcJql = JiraJql.build(listOf("A"), snapshot, ZoneId.of("UTC")).getOrThrow()

        assertTrue(berlinJql.contains("14:32"), berlinJql)
        assertTrue(utcJql.contains("12:32"), utcJql)
    }

    // -- quoting and validation ---------------------------------------------------------------

    /**
     * Keys are always quoted, because a JIRA key may collide with a JQL reserved word and an
     * unquoted one then produces a 400 that names neither the key nor the clause.
     */
    @Test
    fun `every key is quoted, including a single one`() {
        assertTrue(JiraJql.build(listOf("AND"), snapshot, berlin).getOrThrow().contains("""("AND")"""))
    }

    @Test
    fun `the user's order is preserved so the preview and the settings read alike`() {
        val jql = JiraJql.build(listOf("ZED", "ALPHA", "MID"), snapshot, berlin).getOrThrow()

        assertTrue(jql.contains("""("ZED","ALPHA","MID")"""), jql)
    }

    /** The injection boundary. Each of these would otherwise end the `project in (...)` clause. */
    @Test
    fun `a key that could break out of the clause is rejected`() {
        listOf(
            """PROJ" OR key is not empty OR "x""",
            "PROJ; DROP",
            "PROJ)",
            "PROJ KEY",
            "'PROJ'",
            "",
            "1PROJ",
        ).forEach { bad ->
            val result = JiraJql.build(listOf(bad), snapshot, berlin)

            assertTrue(result.isFailure, "accepted a bad project key: $bad")
            assertIs<JiraFailure.InvalidProjectKey>(result.exceptionOrNull())
        }
    }

    /** The settings screen has to say which chip to fix, so the failure names the bad keys. */
    @Test
    fun `the failure names every offending key`() {
        val failure = assertIs<JiraFailure.InvalidProjectKey>(
            JiraJql.build(listOf("GOOD", "bad key", "also bad!"), snapshot, berlin).exceptionOrNull(),
        )

        assertEquals(listOf("bad key", "also bad!"), failure.keys)
    }

    @Test
    fun `keys JIRA itself allows are accepted, including underscores and lower case`() {
        assertTrue(JiraJql.build(listOf("Project_CRPT_2", "abc"), snapshot, berlin).isSuccess)
    }

    /**
     * Never an unbounded query. Falling back to "everything" would import an entire instance and,
     * far worse, would leave phase 5's sweep treating every project as configured.
     */
    @Test
    fun `an empty project list is refused rather than defaulted`() {
        val result = JiraJql.build(emptyList(), snapshot, berlin)

        assertIs<JiraFailure.NoProjectsConfigured>(result.exceptionOrNull())
    }

    // -- the preview --------------------------------------------------------------------------

    /**
     * The preview has no run, so it shows the shape with the bound as a placeholder. Inventing a
     * timestamp would read as a promise about *when* the import will cut off.
     */
    @Test
    fun `the preview shows the shape without inventing a timestamp`() {
        val preview = JiraJql.preview(listOf("ProjectCRPT")).getOrThrow()

        assertEquals(
            """project in ("ProjectCRPT") AND created <= "<import start time>" ORDER BY key ASC""",
            preview,
        )
    }

    @Test
    fun `the preview validates keys the same way the real query does`() {
        assertIs<JiraFailure.InvalidProjectKey>(JiraJql.preview(listOf("bad key")).exceptionOrNull())
        assertIs<JiraFailure.NoProjectsConfigured>(JiraJql.preview(emptyList()).exceptionOrNull())
    }
}
