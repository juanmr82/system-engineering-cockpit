package com.sec.security

import com.sec.config.Neo4jSettings
import com.sec.graph.GraphDriver
import com.sec.graph.executeRead
import com.sec.meta.MetaSchema
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.neo4j.driver.Query
import org.testcontainers.containers.Neo4jContainer
import kotlin.test.assertEquals

// The :User display-name cache (docs/req-review-comment-threads.md §2.2), against a real Neo4j
// Community image (CLAUDE.md §7).
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("docker")
class UserDirectoryTest {

    private val neo4j = Neo4jContainer(
        "neo4j:" + System.getProperty("sec.test.neo4jImage", "2026.06.0-community"),
    ).withoutAuthentication()

    private lateinit var graphDriver: GraphDriver
    private lateinit var userDirectory: UserDirectory

    @BeforeAll
    fun setUp() {
        neo4j.start()
        graphDriver = GraphDriver(Neo4jSettings(neo4j.boltUrl, "neo4j", "neo4j", "ignored"))
        graphDriver.verifyConnectivity()
        runBlocking { MetaSchema.apply(graphDriver) }
        userDirectory = UserDirectory(graphDriver)
    }

    @AfterAll
    fun tearDown() {
        graphDriver.close()
        neo4j.stop()
    }

    private suspend fun readUser(sub: String): Pair<String, String>? =
        graphDriver.executeRead(
            Query("CYPHER 25 MATCH (u:User {__id: \$sub}) RETURN u.__name AS name, u.username AS username", mapOf("sub" to sub)),
        ) { records -> records.singleOrNull()?.let { it.get("name").asString() to it.get("username").asString() } }

    // O4 in the spec: the cache is only as fresh as the last sign-in, which is the trade a
    // best-effort MERGE...SET makes on purpose. Signing in twice with a changed name is what
    // shows the mechanism does what §2.2 asks — overwritten every sign-in, never merged.
    @Test
    fun `signing in twice with a changed name overwrites the cache`() = runBlocking {
        userDirectory.upsert(sub = "sub-1", name = "Elena K.", username = "ekowalski")
        assertEquals("Elena K." to "ekowalski", readUser("sub-1"))

        userDirectory.upsert(sub = "sub-1", name = "Elena Kowalski", username = "ekowalski")
        assertEquals("Elena Kowalski" to "ekowalski", readUser("sub-1"))
    }

    @Test
    fun `two different subs get two different nodes`() = runBlocking {
        userDirectory.upsert(sub = "sub-a", name = "Alice", username = "alice")
        userDirectory.upsert(sub = "sub-b", name = "Bob", username = "bob")

        assertEquals("Alice" to "alice", readUser("sub-a"))
        assertEquals("Bob" to "bob", readUser("sub-b"))
    }
}
