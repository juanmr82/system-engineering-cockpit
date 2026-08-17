package com.sec.source.jira

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The JQL builder (spec §8, ADR 0018).
 *
 * There is no project allow-list any more, so this file no longer covers an injection boundary —
 * just the fixed clauses every import sends, and the one place they vary: the timezone the snapshot
 * bound is rendered in.
 */
class JiraJqlTest {

    private val snapshot: Instant = Instant.parse("2026-08-11T12:32:45Z")
    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")

    @Test
    fun `the query bounds the snapshot and orders by key`() {
        val jql = JiraJql.build(snapshot, berlin)

        assertEquals("""created <= "2026/08/11 14:32" ORDER BY key ASC""", jql)
    }

    /**
     * JIRA's literal format, which is not ISO-8601. `Instant.toString()` would put a `T` in the
     * middle and JIRA answers 400 with a message about JQL syntax — an error that sends you
     * looking at the clauses rather than at the timestamp.
     */
    @Test
    fun `the created bound is formatted the way JQL reads it, not ISO-8601`() {
        val jql = JiraJql.build(snapshot, berlin)

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
        val berlinJql = JiraJql.build(snapshot, berlin)
        val utcJql = JiraJql.build(snapshot, ZoneId.of("UTC"))

        assertTrue(berlinJql.contains("14:32"), berlinJql)
        assertTrue(utcJql.contains("12:32"), utcJql)
    }
}
