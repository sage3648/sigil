package sigil.registry.api

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import com.mongodb.kotlin.client.coroutine.MongoClient
import sigil.registry.store.InMemoryStore
import sigil.registry.store.MongoStore

fun main() {
    val port = System.getenv("SIGIL_PORT")?.toIntOrNull() ?: 8080
    val mongoUri = System.getenv("MONGO_URI")

    val store = if (mongoUri != null) {
        val client = MongoClient.create(mongoUri)
        val database = client.getDatabase(System.getenv("MONGO_DB") ?: "sigil_registry")
        MongoStore(database.getCollection("nodes"))
    } else {
        InMemoryStore()
    }

    val service = RegistryService(store)

    embeddedServer(Netty, port = port) {
        configureServer(service)
    }.start(wait = true)
}

fun Application.configureServer(service: RegistryService) {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }

    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(cause.message ?: "Bad request")
            )
        }
        exception<NoSuchElementException> { call, cause ->
            call.respond(
                HttpStatusCode.NotFound,
                ErrorResponse(cause.message ?: "Not found")
            )
        }
        exception<Exception> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("Internal server error", cause.message)
            )
        }
    }

    install(CORS) {
        anyHost()
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.ContentType)
    }

    routing {
        registryRoutes(service)
    }
}
