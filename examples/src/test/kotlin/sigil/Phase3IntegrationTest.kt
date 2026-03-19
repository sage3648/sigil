package sigil

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import sigil.ast.PrimitiveTypes
import sigil.codegen.jvm.ContractViolation
import sigil.examples.SigilPrograms
import sigil.interop.SigilModule
import sigil.registry.api.PublishMetadata
import sigil.registry.api.PublishRequest
import sigil.registry.api.RegistryService
import sigil.registry.api.SearchRequest
import sigil.registry.store.InMemoryStore
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Phase3IntegrationTest {

    // --- (a) Full Kotlin Interop Flow ---

    @Test
    fun `compile Sigil source and call from Kotlin`() {
        val source = "fn add(a: Int, b: Int) -> Int { a + b }"
        val module = SigilModule.compile(source)
        val result = module.call<Long>("add", 3L, 4L)
        assertEquals(7L, result)
    }

    @Test
    fun `multiple functions from one compilation`() {
        val source = """
            fn add(a: Int, b: Int) -> Int { a + b }
            fn mul(a: Int, b: Int) -> Int { a * b }
            fn negate(x: Int) -> Int { -x }
        """.trimIndent()
        val module = SigilModule.compile(source)

        assertEquals(7L, module.call<Long>("add", 3L, 4L))
        assertEquals(12L, module.call<Long>("mul", 3L, 4L))
        assertEquals(-5L, module.call<Long>("negate", 5L))

        val fns = module.listFunctions()
        assertEquals(3, fns.size)
    }

    @Test
    fun `contract enforcement at Kotlin boundary`() {
        val source = """
            fn positive(x: Int) -> Int {
                requires x > 0
                x
            }
        """.trimIndent()
        val module = SigilModule.compile(source)

        // Valid call succeeds
        assertEquals(5L, module.call<Long>("positive", 5L))

        // Contract violation throws
        val ex = assertThrows<java.lang.reflect.InvocationTargetException> {
            module.call<Long>("positive", -1L)
        }
        assertTrue(ex.cause is ContractViolation)
    }

    // --- (b) Agent Workflow Simulation: Compiler + Registry ---

    @Test
    fun `publish compiled functions and search by type signature`() = runTest {
        val store = InMemoryStore()
        val registry = RegistryService(store)

        // Publish Sigil source to registry
        val source = """
            fn add(a: Int, b: Int) -> Int { a + b }
            fn is_positive(x: Int) -> Bool { x > 0 }
        """.trimIndent()
        val responses = registry.publish(
            PublishRequest(
                source = source,
                metadata = PublishMetadata(
                    intent = "arithmetic utilities",
                    author = "integration-test",
                    domainTags = listOf("math")
                )
            )
        )

        assertEquals(2, responses.size)
        assertTrue(responses.all { it.verificationTier >= 1 })

        // Search by type signature: (Int, Int) -> Int
        val searchResults = registry.search(
            SearchRequest(
                inputTypes = listOf(PrimitiveTypes.INT, PrimitiveTypes.INT),
                outputType = PrimitiveTypes.INT
            )
        )
        assertTrue(searchResults.isNotEmpty(), "Should find functions matching (Int, Int) -> Int")
        val foundHashes = searchResults.map { it.hash }
        val addHash = responses.first { it.nodeType == "FnDef" }.hash
        assertTrue(addHash in foundHashes, "Published 'add' function should be found in search")

        // Search by domain tag
        val tagResults = registry.search(SearchRequest(domainTags = listOf("math")))
        assertTrue(tagResults.isNotEmpty(), "Should find functions by domain tag")

        // Verify the found node
        val node = registry.getNode(addHash)
        assertNotNull(node)
        assertEquals("FnDef", node.nodeType)
    }

    @Test
    fun `publish and retrieve registry stats`() = runTest {
        val store = InMemoryStore()
        val registry = RegistryService(store)

        registry.publish(PublishRequest(source = SigilPrograms.MATH))
        registry.publish(PublishRequest(source = SigilPrograms.VALIDATORS))

        val stats = registry.getStats()
        assertTrue(stats.totalNodes >= 8, "Should have at least 8 published functions")
        assertTrue(stats.countsByType.containsKey("FnDef"))
    }

    // --- (c) Dogfooding Validation ---

    @Test
    fun `math programs compile and produce correct results`() {
        val module = SigilModule.compile(SigilPrograms.MATH)

        assertEquals(42L, module.call<Long>("abs", -42L))
        assertEquals(0L, module.call<Long>("abs", 0L))
        assertEquals(50L, module.call<Long>("clamp", 50L, 0L, 100L))
        assertEquals(0L, module.call<Long>("clamp", -5L, 0L, 100L))
        assertEquals(100L, module.call<Long>("clamp", 200L, 0L, 100L))
        assertEquals(10L, module.call<Long>("max", 10L, 5L))
        assertEquals(5L, module.call<Long>("min", 10L, 5L))
    }

    @Test
    fun `validator programs compile and produce correct results`() {
        val module = SigilModule.compile(SigilPrograms.VALIDATORS)

        assertTrue(module.call<Boolean>("is_positive", 5L))
        assertTrue(module.call<Boolean>("is_non_negative", 0L))
        assertTrue(module.call<Boolean>("is_in_range", 50L, 0L, 100L))
        assertEquals(5L, module.call<Long>("safe_divide", 10L, 2L))

        // Contract violation on division by zero
        val ex = assertThrows<java.lang.reflect.InvocationTargetException> {
            module.call<Long>("safe_divide", 10L, 0L)
        }
        assertTrue(ex.cause is ContractViolation)
    }

    @Test
    fun `scoring programs compile and produce correct results`() {
        val module = SigilModule.compile(SigilPrograms.SCORING)

        assertEquals(35L, module.call<Long>("weighted_score", 10L, 5L, 3L))
        assertEquals(0L, module.call<Long>("weighted_score", -10L, 5L, 1L))
        assertEquals(37L, module.call<Long>("normalize_score", 75L, 200L))
        assertEquals(1L, module.call<Long>("rank_tier", 95L))
        assertEquals(4L, module.call<Long>("rank_tier", 30L))
    }

    // --- (d) Performance Sanity Check ---

    @Test
    fun `sigil function completes alongside equivalent kotlin`() {
        val module = SigilModule.compile("fn add(a: Int, b: Int) -> Int { a + b }")

        // Run Sigil
        val sigilResult = module.call<Long>("add", 100L, 200L)
        assertEquals(300L, sigilResult)

        // Run equivalent Kotlin
        val kotlinResult = 100L + 200L
        assertEquals(300L, kotlinResult)

        // Both produce the same answer
        assertEquals(kotlinResult, sigilResult)

        // Run many iterations to verify stability
        repeat(1000) { i ->
            val r = module.call<Long>("add", i.toLong(), 1L)
            assertEquals(i.toLong() + 1L, r)
        }
    }

    @Test
    fun `sigil clamp performance sanity`() {
        val module = SigilModule.compile(SigilPrograms.MATH)
        val kotlinClamp = { x: Long, lo: Long, hi: Long ->
            if (x < lo) lo else if (x > hi) hi else x
        }

        // Warm up
        repeat(100) {
            module.call<Long>("clamp", 50L, 0L, 100L)
            kotlinClamp(50L, 0L, 100L)
        }

        // Run both and verify correctness
        val testValues = listOf(-10L, 0L, 25L, 50L, 75L, 100L, 150L)
        for (v in testValues) {
            assertEquals(
                kotlinClamp(v, 0L, 100L),
                module.call<Long>("clamp", v, 0L, 100L),
                "clamp($v, 0, 100) should match"
            )
        }
    }
}
