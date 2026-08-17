package com.sec.graph.cypher

import com.sec.domain.NodeLabel.USER
import com.sec.domain.Prop.ID
import com.sec.domain.Prop.NAME
import com.sec.domain.UserProp.USERNAME

// Cypher for the `:User` display-name cache (`docs/req-review-comment-threads.md` §2.2). `:User`
// is not `:__Meta` — it anchors to the identity directory, not to the imported graph — so it is
// written by `security/UserDirectory.kt` directly rather than through the R2-guarded meta writer,
// the same split `:__Group` and `AccessReconciler`/`AccessCypher.RESOLVE_GROUPS` already have.
public object UserCypher {
    /**
     * Called once per sign-in. Best-effort and overwritten every time — a display-name cache,
     * never gating a decision (O4 in the spec: only as fresh as the last sign-in).
     */
    public val UPSERT: String = """
        CYPHER 25
        MERGE (u:$USER {$ID: ${'$'}sub})
        SET u.$NAME = ${'$'}name, u.$USERNAME = ${'$'}username
    """
}
