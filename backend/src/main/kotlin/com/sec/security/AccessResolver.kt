package com.sec.security

import com.sec.graph.GraphDriver
import com.sec.graph.cypher.AccessCypher
import com.sec.graph.executeWrite
import org.neo4j.driver.Query
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * What a caller may see, resolved once from the `groups` claim (`docs/features/access-control.md`
 * §5). `categoryIds` are `__metaId` values, deduplicated and sorted — the sort is what lets the
 * *set of groups* double as a cache key regardless of the order Keycloak happened to list them in.
 */
public data class AccessSet(
    public val seesAll: Boolean,
    public val categoryIds: List<String>,
) {
    public companion object {
        /** A caller in no group at all (§5 step 1): no grant, no `everyGroup` category, nothing. */
        public val NONE: AccessSet = AccessSet(seesAll = false, categoryIds = emptyList())

        /**
         * A caller in a `seesAll` group — what [AccessResolver.resolveFromGraph] returns for one.
         *
         * `categoryIds` is empty on purpose and is not a shortcut: §5 step 3 says `seesAll` answers
         * everything on its own, so the predicate never reads `$acl` when it is true.
         */
        public val SEES_ALL: AccessSet = AccessSet(seesAll = true, categoryIds = emptyList())
    }
}

/**
 * Token groups to an [AccessSet]. The only class that reads the `groups` claim for this purpose
 * (`docs/features/access-control.md` §5) — every filtered read path gets its `$seesAll`/`$acl`
 * parameters from here, by way of `graph/Read.kt`, never by assembling them itself.
 *
 * Cached on the **group set**, not the user: two hundred users in the same four groups share one
 * entry, so a login storm is not two hundred resolves. Invalidation is a single version counter
 * rather than a TTL — an entry carries the version it was computed at and is recomputed the moment
 * that stops matching, which is exact where a TTL would only be approximately right, and one
 * invalidation mechanism is enough (§5 "do not add a TTL as well").
 */
public class AccessResolver(private val graphDriver: GraphDriver) {
    private val version = AtomicLong(0)
    private val cache = ConcurrentHashMap<List<String>, CacheEntry>()

    public suspend fun resolve(groups: List<String>): AccessSet {
        if (groups.isEmpty()) {
            return AccessSet.NONE
        }

        val key = groups.distinct().sorted()
        val currentVersion = version.get()
        cache[key]?.let { entry -> if (entry.version == currentVersion) return entry.set }

        val resolved = resolveFromGraph(key)
        cache[key] = CacheEntry(currentVersion, resolved)
        return resolved
    }

    /**
     * Called by `AccessAdminService` (phase 6) after every write to categories, grants or
     * defaults. Every cache entry is stale the instant this runs; nothing is cleared eagerly, so a
     * resolve already in flight still finishes against the version it started with instead of
     * racing a clear.
     */
    public fun invalidate() {
        version.incrementAndGet()
    }

    /**
     * The one query this class issues. It both mirrors every claimed group (`ON CREATE`/`SET`,
     * throttled implicitly by the cache above rather than by a separate TTL — see class doc) and
     * reads back what those groups may see, so a cold resolve is one round trip, not two.
     */
    private suspend fun resolveFromGraph(groupKeys: List<String>): AccessSet =
        graphDriver.executeWrite(
            Query(
                AccessCypher.RESOLVE_GROUPS,
                mapOf("groupKeys" to groupKeys, "now" to Instant.now().toString()),
            ),
        ) { records ->
            val record = records.single()
            // §5 step 3: seesAll answers everything on its own, so the category list carries
            // nothing when it is true — the predicate never needs $acl in that case either.
            if (record.get("seesAll").asBoolean(false)) {
                AccessSet.SEES_ALL
            } else {
                AccessSet(
                    seesAll = false,
                    categoryIds = record.get("categoryIds").asList { it.asString() }.distinct().sorted(),
                )
            }
        }

    private data class CacheEntry(val version: Long, val set: AccessSet)
}
