package com.sec.meta

import com.sec.domain.GroupProp.KEY as GROUP_KEY
import com.sec.domain.MetaProp.ATTRIBUTE_NAME
import com.sec.domain.MetaProp.KEY as ACCESS_CATEGORY_KEY
import com.sec.domain.NodeLabel.ACCESS_CATEGORY
import com.sec.domain.NodeLabel.ATTRIBUTE_SETTING
import com.sec.domain.NodeLabel.GROUP
import com.sec.domain.NodeLabel.META
import com.sec.domain.NodeLabel.POLICY
import com.sec.domain.Prop.META_ID
import com.sec.graph.GraphDriver
import com.sec.graph.executeWrite
import io.github.oshai.kotlinlogging.KotlinLogging
import org.neo4j.driver.Query

private val logger = KotlinLogging.logger {}

/**
 * Schema for the `:__Meta` labels, plus the two access-control nodes access-control.md §4.3
 * deliberately places here alongside them: `:__Group` and (later, phase 3) `:__AccessDefault` are
 * application-owned rather than `:__Meta` (ADR 0016 §6.2), but they are schema this backend owns
 * and applies at the same startup pass, so they live beside it rather than inventing a second file
 * for two constraints.
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
    /**
     * Schema changes cannot share a transaction with anything else, so each runs on its own.
     *
     * The labels and property names are interpolated from the constants, the same as every
     * statement in `graph/cypher/`; the constraint and index *names* stay literal, because those
     * are this file's own database objects rather than graph vocabulary anything else addresses.
     *
     * Public so `GraphNamesTest` can check the labels in them against the constants, the same way
     * it checks `graph/cypher/` — these statements name `:__Meta` labels too, and a typo in one
     * creates a constraint on a label nothing else uses instead of failing.
     */
    public val statements: List<String> = listOf(
        """
        CYPHER 25
        CREATE CONSTRAINT meta_id_unique IF NOT EXISTS
        FOR (m:$META) REQUIRE m.$META_ID IS UNIQUE
        """,
        // Serves the inverse question to EXISTING_MANDATORY_POLICIES: "which modules mark this
        // attribute mandatory" (docs/features/attribute-policy-checks.md §3). Label-property
        // indexes are per-label, so the policy label needs its own — the :__Meta index would not
        // be used for it.
        """
        CYPHER 25
        CREATE INDEX meta_policy_attribute IF NOT EXISTS
        FOR (p:$POLICY) ON (p.$ATTRIBUTE_NAME)
        """,
        // The same inverse question for the other Shape-B kind: "which modules show this
        // attribute", asked by the summary views in REQ_REVIEW.md §9.3.
        """
        CYPHER 25
        CREATE INDEX meta_attribute_setting IF NOT EXISTS
        FOR (s:$ATTRIBUTE_SETTING) ON (s.$ATTRIBUTE_NAME)
        """,
        // access-control.md §4.3. __metaId's constraint above already covers :__AccessCategory as
        // a :__Meta node; this is the *second*, human-typed key an admin actually names a category
        // by, and Community has no composite constraint to lean on instead.
        """
        CYPHER 25
        CREATE CONSTRAINT access_category_key IF NOT EXISTS
        FOR (c:$ACCESS_CATEGORY) REQUIRE c.$ACCESS_CATEGORY_KEY IS UNIQUE
        """,
        // :__Group is not :__Meta (ADR 0016 §6.2) but is still this backend's own schema to apply.
        """
        CYPHER 25
        CREATE CONSTRAINT group_key IF NOT EXISTS
        FOR (g:$GROUP) REQUIRE g.$GROUP_KEY IS UNIQUE
        """,
    )

    public suspend fun apply(graphDriver: GraphDriver) {
        statements.forEach { statement ->
            graphDriver.executeWrite(Query(statement)) { }
        }
        logger.info { "Applied :$META schema (${statements.size} statements)" }
    }
}
