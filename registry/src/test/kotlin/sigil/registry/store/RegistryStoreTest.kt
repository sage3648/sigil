package sigil.registry.store

import kotlinx.coroutines.test.runTest
import sigil.ast.VerificationRecord
import kotlin.test.*

class RegistryStoreTest {

    private lateinit var store: RegistryStore

    private fun testNode(
        hash: String = "abc123",
        nodeType: String = "FnDef",
        dependencies: List<String> = emptyList()
    ) = RegistryNode(
        hash = hash,
        nodeType = nodeType,
        ast = """{"type":"$nodeType","name":"test"}""",
        semanticSignature = SemanticSignature(
            inputTypes = listOf("sigil:int"),
            outputType = "sigil:bool",
            effects = emptyList(),
            requiresPredicates = emptyList(),
            ensuresPredicates = emptyList()
        ),
        metadata = NodeMetadata(
            aliases = listOf("testFn"),
            intent = "A test function",
            author = "test-author",
            createdAt = System.currentTimeMillis(),
            domainTags = listOf("testing")
        ),
        verification = emptyList(),
        dependents = emptyList(),
        dependencies = dependencies
    )

    @BeforeTest
    fun setUp() {
        store = InMemoryStore()
    }

    @Test
    fun `store and get node`() = runTest {
        val node = testNode()
        val hash = store.store(node)
        assertEquals("abc123", hash)

        val retrieved = store.get(hash)
        assertNotNull(retrieved)
        assertEquals(node.hash, retrieved.hash)
        assertEquals(node.nodeType, retrieved.nodeType)
        assertEquals(node.ast, retrieved.ast)
    }

    @Test
    fun `get returns null for missing hash`() = runTest {
        assertNull(store.get("nonexistent"))
    }

    @Test
    fun `exists returns true for stored node`() = runTest {
        store.store(testNode())
        assertTrue(store.exists("abc123"))
    }

    @Test
    fun `exists returns false for missing hash`() = runTest {
        assertFalse(store.exists("nonexistent"))
    }

    @Test
    fun `delete removes node`() = runTest {
        store.store(testNode())
        assertTrue(store.delete("abc123"))
        assertNull(store.get("abc123"))
    }

    @Test
    fun `delete returns false for missing hash`() = runTest {
        assertFalse(store.delete("nonexistent"))
    }

    @Test
    fun `listAll returns stored nodes`() = runTest {
        store.store(testNode("h1"))
        store.store(testNode("h2"))
        store.store(testNode("h3"))

        val all = store.listAll()
        assertEquals(3, all.size)
    }

    @Test
    fun `listAll respects limit and offset`() = runTest {
        repeat(5) { store.store(testNode("h$it")) }

        val page = store.listAll(limit = 2, offset = 1)
        assertEquals(2, page.size)
    }

    @Test
    fun `getByNodeType filters correctly`() = runTest {
        store.store(testNode("h1", nodeType = "FnDef"))
        store.store(testNode("h2", nodeType = "TypeDef"))
        store.store(testNode("h3", nodeType = "FnDef"))

        val fnDefs = store.getByNodeType("FnDef")
        assertEquals(2, fnDefs.size)
        assertTrue(fnDefs.all { it.nodeType == "FnDef" })

        val typeDefs = store.getByNodeType("TypeDef")
        assertEquals(1, typeDefs.size)
    }

    @Test
    fun `updateVerification appends record`() = runTest {
        store.store(testNode())

        val record = VerificationRecord(
            tier = 1,
            tool = "sigil-verifier",
            timestamp = System.currentTimeMillis(),
            details = mapOf("status" to "passed")
        )
        store.updateVerification("abc123", record)

        val node = store.get("abc123")
        assertNotNull(node)
        assertEquals(1, node.verification.size)
        assertEquals("sigil-verifier", node.verification[0].tool)

        val record2 = VerificationRecord(
            tier = 2,
            tool = "formal-prover",
            timestamp = System.currentTimeMillis()
        )
        store.updateVerification("abc123", record2)

        val updated = store.get("abc123")
        assertNotNull(updated)
        assertEquals(2, updated.verification.size)
    }

    @Test
    fun `updateVerification throws for missing node`() = runTest {
        assertFailsWith<NoSuchElementException> {
            store.updateVerification("nonexistent", VerificationRecord(1, "tool", 0L))
        }
    }

    @Test
    fun `addDependent and getDependents`() = runTest {
        store.store(testNode("h1"))
        store.store(testNode("h2"))

        store.addDependent("h1", "h2")
        val dependents = store.getDependents("h1")
        assertEquals(listOf("h2"), dependents)
    }

    @Test
    fun `addDependent is idempotent`() = runTest {
        store.store(testNode("h1"))

        store.addDependent("h1", "h2")
        store.addDependent("h1", "h2")

        val dependents = store.getDependents("h1")
        assertEquals(1, dependents.size)
    }

    @Test
    fun `getDependents returns empty for missing node`() = runTest {
        assertEquals(emptyList(), store.getDependents("nonexistent"))
    }

    @Test
    fun `getDependencies returns stored dependencies`() = runTest {
        store.store(testNode("h1", dependencies = listOf("dep1", "dep2")))

        val deps = store.getDependencies("h1")
        assertEquals(listOf("dep1", "dep2"), deps)
    }

    @Test
    fun `getDependencies returns empty for missing node`() = runTest {
        assertEquals(emptyList(), store.getDependencies("nonexistent"))
    }
}
