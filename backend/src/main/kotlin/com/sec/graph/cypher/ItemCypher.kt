package com.sec.graph.cypher

// Cypher as named constants, one file per domain (CLAUDE.md §5). Every statement is prefixed
// with "CYPHER 25" so behaviour is deterministic regardless of how the database was created.
// Never build these strings by concatenating user or source data — pass maps as parameters.
public object ItemCypher {
    public const val FIND_BY_ID: String = """
        CYPHER 25
        MATCH (n:SEItem { __id: ${'$'}id })
        RETURN n
        LIMIT 1
    """
}
