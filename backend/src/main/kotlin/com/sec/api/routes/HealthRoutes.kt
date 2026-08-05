package com.sec.api.routes

import com.sec.api.respondProblem
import com.sec.graph.GraphDriver
import com.sec.graph.cypher.SystemCypher
import com.sec.graph.executeRead
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.neo4j.driver.Query

private val logger = KotlinLogging.logger {}

// Liveness and readiness are different questions and must not share an endpoint: an orchestrator
// restarts on a failed liveness probe but only withholds traffic on a failed readiness probe.
// /health answers "the process is running"; only /ready touches Neo4j.
public fun Route.healthRoutes(graphDriver: GraphDriver) {
    get("/api/v1/health") {
        call.respondText("ok")
    }

    get("/api/v1/ready") {
        val reachable = runCatching {
            graphDriver.executeRead(Query(SystemCypher.PING)) { records -> records.isNotEmpty() }
        }.getOrElse { cause ->
            logger.warn(cause) { "Readiness probe failed: the graph is not answering" }
            false
        }

        if (reachable) {
            call.respondText("ready")
        } else {
            call.respondProblem(
                HttpStatusCode.ServiceUnavailable,
                "Not ready",
                "The graph database is not answering.",
            )
        }
    }
}
