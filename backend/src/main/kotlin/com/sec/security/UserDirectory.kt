package com.sec.security

import com.sec.graph.GraphDriver
import com.sec.graph.cypher.UserCypher
import com.sec.graph.executeWrite
import org.neo4j.driver.Query

/**
 * The `:User` display-name cache (`docs/req-review-comment-threads.md` §2.2) — a comment author's
 * `sub` is opaque, and this is what turns "Added by f47ac10b-58cc…" into a name.
 *
 * **Not `:__Meta`, and not written through `MetaWriter`** — it anchors to the identity directory,
 * not to the imported graph, the same reasoning that keeps `:__Group` out of the R2 write path.
 * **A display cache, not an identity store: it never gates a decision.** Group membership and
 * roles are checked live against Keycloak's claims (`AccessResolver`); if this node disappeared
 * entirely, authorization would be unaffected — only `__createdBy` on old comments would fall
 * back to showing a raw `sub` (O3 in the spec).
 */
public class UserDirectory(private val graphDriver: GraphDriver) {
    /**
     * Called once per sign-in (`AuthRoutes.kt`'s callback), with whatever the ID token carried
     * beyond the three depended-on claims. Best-effort and overwritten every time, so a display
     * name change in the IdP shows up next login — and so it is only ever as fresh as someone's
     * last sign-in (O4), which is the trade every cache like this makes.
     */
    public suspend fun upsert(sub: String, name: String, username: String) {
        graphDriver.executeWrite(
            Query(UserCypher.UPSERT, mapOf("sub" to sub, "name" to name, "username" to username)),
        ) { }
    }
}
