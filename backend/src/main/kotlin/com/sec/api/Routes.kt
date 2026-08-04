package com.sec.api

import com.sec.graph.GraphDriver
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

// Route registration only — handlers delegate to domain/source/meta, they don't hold logic.
// {ref} is the base64url encoding of __id (R5), decoded by one route parameter converter,
// never inline in a handler.
public fun Application.configureRouting(graphDriver: GraphDriver) {
    routing {
        get("/api/v1/health") {
            call.respondText("ok")
        }

        // GET  /api/v1/tree
        // GET  /api/v1/items/{ref}
        // GET  /api/v1/items/{ref}/children
        // GET  /api/v1/items/{ref}/traces
        // GET  /api/v1/items/{ref}/annotations
        // POST /api/v1/items/{ref}/annotations
        // PATCH|DELETE /api/v1/annotations/{ref}
        // GET  /api/v1/modules
        // GET  /api/v1/modules/{ref}/attributes
        // GET  /api/v1/config/navigation
        // POST /api/v1/cypher/explain
        // POST /api/v1/cypher/run
        // See CLAUDE.md §5 "API shape" and docs/CYPHER_API_DESIGN.md.
    }
}
