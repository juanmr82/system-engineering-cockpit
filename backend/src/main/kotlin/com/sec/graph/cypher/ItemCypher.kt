package com.sec.graph.cypher

import com.sec.domain.NodeLabel.SE_ITEM
import com.sec.domain.Prop.ID

// Cypher as named constants, one file per domain (CLAUDE.md §5). Every statement is prefixed
// with "CYPHER 25" so behaviour is deterministic regardless of how the database was created.
// Never build these strings by concatenating user or source data — pass maps as parameters.
//
// Every graph name is interpolated from a constant and never spelled out, so renaming one is a
// single edit in `domain/GraphNames.kt` or `source/doors/DoorsNames.kt` (ADR 0010). The names are
// imported one by one so the templates stay short and the Cypher stays readable; `${'$'}` is a
// query *parameter*, which is a different thing entirely.
public object ItemCypher {
    public val FIND_BY_ID: String = """
        CYPHER 25
        MATCH (n:$SE_ITEM { $ID: ${'$'}id })
        WHERE ${AccessCypher.visible("n")}
        RETURN n
        LIMIT 1
    """
}
