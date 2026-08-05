package com.sec.meta

import com.sec.graph.GraphDriver
import com.sec.graph.executeWrite
import io.github.oshai.kotlinlogging.KotlinLogging
import org.neo4j.driver.Query

private val logger = KotlinLogging.logger {}

/**
 * Schema for the `:__Meta` labels, and for those only.
 *
 * The backend owns Tier-2 schema; the importers own the schema for every imported label
 * (CLAUDE.md §10). That split must not blur — nothing here may mention `:SEItem`, `:DOORSObject`
 * or any other imported label, and nothing in an importer may create a `:__Meta` constraint.
 *
 * Community cannot enforce property existence, so uniqueness is the only constraint available
 * (§7) and `__metaId` is the key that owns it. Applied on every boot: each statement is
 * `IF NOT EXISTS`, so this is idempotent.
 */
public object MetaSchema {
    // Schema changes cannot share a transaction with anything else, so each runs on its own.
    private val STATEMENTS: List<String> = listOf(
        """
        CYPHER 25
        CREATE CONSTRAINT meta_id_unique IF NOT EXISTS
        FOR (m:__Meta) REQUIRE m.__metaId IS UNIQUE
        """,
        // Serves the inverse question to EXISTING_MANDATORY_POLICIES: "which modules mark this
        // attribute mandatory" (docs/features/attribute-policy-checks.md §3). Label-property
        // indexes are per-label, so :__Policy needs its own — :__Meta's would not be used.
        """
        CYPHER 25
        CREATE INDEX meta_policy_attribute IF NOT EXISTS
        FOR (p:__Policy) ON (p.attributeName)
        """,
        // The same inverse question for the other Shape-B kind: "which modules show this
        // attribute", asked by the summary views in REQ_REVIEW.md §9.3.
        """
        CYPHER 25
        CREATE INDEX meta_attribute_setting IF NOT EXISTS
        FOR (s:__AttributeSetting) ON (s.attributeName)
        """,
    )

    public suspend fun apply(graphDriver: GraphDriver) {
        STATEMENTS.forEach { statement ->
            graphDriver.executeWrite(Query(statement)) { }
        }
        logger.info { "Applied :__Meta schema (${STATEMENTS.size} statements)" }
    }
}
