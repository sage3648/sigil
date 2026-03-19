package sigil.registry.api

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.registryRoutes(service: RegistryService) {

    get("/health") {
        call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
    }

    route("/registry") {

        post("/publish") {
            val request = call.receive<PublishRequest>()
            val responses = service.publish(request)
            call.respond(HttpStatusCode.Created, responses)
        }

        post("/search") {
            val request = call.receive<SearchRequest>()
            val results = service.search(request)
            call.respond(HttpStatusCode.OK, results)
        }

        get("/node/{hash}") {
            val hash = call.parameters["hash"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing hash parameter"))
            val node = service.getNode(hash)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Node not found: $hash"))
            call.respond(HttpStatusCode.OK, node)
        }

        get("/node/{hash}/dependents") {
            val hash = call.parameters["hash"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing hash parameter"))
            val dependents = service.getDependents(hash)
            call.respond(HttpStatusCode.OK, dependents)
        }

        post("/node/{hash}/deprecate") {
            val hash = call.parameters["hash"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing hash parameter"))
            val result = service.deprecate(hash)
            call.respond(HttpStatusCode.OK, result)
        }

        get("/stats") {
            val stats = service.getStats()
            call.respond(HttpStatusCode.OK, stats)
        }
    }
}
