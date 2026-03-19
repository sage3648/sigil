package sigil.registry.api

import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import sigil.registry.store.InMemoryStore
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApiTest {

    private val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
    }

    private fun apiTest(block: suspend ApplicationTestBuilder.(io.ktor.client.HttpClient) -> Unit) = testApplication {
        val store = InMemoryStore()
        val service = RegistryService(store)
        application {
            configureServer(service)
        }
        val client = createClient {
            install(ContentNegotiation) {
                json(json)
            }
        }
        block(client)
    }

    @Test
    fun `health endpoint returns 200`() = apiTest { client ->
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `publish a simple function`() = apiTest { client ->
        val source = "fn add(x: Int, y: Int) -> Int { x + y }"
        val response = client.post("/registry/publish") {
            contentType(ContentType.Application.Json)
            setBody(PublishRequest(source = source))
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val results = response.body<List<PublishResponse>>()
        assertEquals(1, results.size)
        assertEquals("FnDef", results[0].nodeType)
        assertTrue(results[0].hash.isNotBlank())
        assertTrue(results[0].verificationTier >= 1)
    }

    @Test
    fun `get published node by hash`() = apiTest { client ->
        val source = "fn identity(x: Int) -> Int { x }"
        val publishResponse = client.post("/registry/publish") {
            contentType(ContentType.Application.Json)
            setBody(PublishRequest(source = source))
        }
        val results = publishResponse.body<List<PublishResponse>>()
        val hash = results[0].hash

        val getResponse = client.get("/registry/node/$hash")
        assertEquals(HttpStatusCode.OK, getResponse.status)
        val node = getResponse.body<NodeResponse>()
        assertEquals(hash, node.hash)
        assertEquals("FnDef", node.nodeType)
    }

    @Test
    fun `get missing node returns 404`() = apiTest { client ->
        val response = client.get("/registry/node/nonexistent_hash")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `search returns results`() = apiTest { client ->
        val source = "fn double(x: Int) -> Int { x }"
        client.post("/registry/publish") {
            contentType(ContentType.Application.Json)
            setBody(PublishRequest(
                source = source,
                metadata = PublishMetadata(aliases = listOf("double"), intent = "doubles a number")
            ))
        }

        val searchResponse = client.post("/registry/search") {
            contentType(ContentType.Application.Json)
            setBody(SearchRequest(textQuery = "double"))
        }
        assertEquals(HttpStatusCode.OK, searchResponse.status)
        val results = searchResponse.body<List<sigil.registry.semantic.SearchResult>>()
        assertTrue(results.isNotEmpty())
    }

    @Test
    fun `publish with bad source returns 400`() = apiTest { client ->
        val response = client.post("/registry/publish") {
            contentType(ContentType.Application.Json)
            setBody(PublishRequest(source = "this is not valid sigil code !!!"))
        }
        assertTrue(response.status == HttpStatusCode.BadRequest || response.status == HttpStatusCode.InternalServerError)
    }

    @Test
    fun `stats endpoint returns counts`() = apiTest { client ->
        val source = "fn inc(x: Int) -> Int { x }"
        client.post("/registry/publish") {
            contentType(ContentType.Application.Json)
            setBody(PublishRequest(source = source))
        }

        val response = client.get("/registry/stats")
        assertEquals(HttpStatusCode.OK, response.status)
        val stats = response.body<StatsResponse>()
        assertEquals(1, stats.totalNodes)
        assertNotNull(stats.countsByType["FnDef"])
    }

    @Test
    fun `deprecate missing node returns 404`() = apiTest { client ->
        val response = client.post("/registry/node/nonexistent/deprecate")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `get dependents returns list`() = apiTest { client ->
        val source = "fn id(x: Int) -> Int { x }"
        val publishResponse = client.post("/registry/publish") {
            contentType(ContentType.Application.Json)
            setBody(PublishRequest(source = source))
        }
        val results = publishResponse.body<List<PublishResponse>>()
        val hash = results[0].hash

        val response = client.get("/registry/node/$hash/dependents")
        assertEquals(HttpStatusCode.OK, response.status)
        val dependents = response.body<List<String>>()
        assertTrue(dependents.isEmpty())
    }
}
