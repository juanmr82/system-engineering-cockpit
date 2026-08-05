package com.sec.graph.cypher

// Statements the service runs about itself rather than about the domain.
public object SystemCypher {
    // The cheapest statement that proves the database is answering: no labels touched, no
    // planner work, so a readiness probe costs nothing even when polled every few seconds.
    public const val PING: String = """
        CYPHER 25
        RETURN 1 AS ok
    """
}
