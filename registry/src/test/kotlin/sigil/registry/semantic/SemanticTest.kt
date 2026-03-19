package sigil.registry.semantic

import kotlinx.coroutines.test.runTest
import sigil.ast.*
import sigil.registry.store.InMemoryStore
import sigil.registry.store.NodeMetadata
import sigil.registry.store.RegistryNode
import sigil.registry.store.SemanticSignature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SemanticTest {

    private val extractor = SemanticExtractor()

    // -- Extraction tests --

    @Test
    fun `extract signature from FnDef`() {
        val fn = FnDef(
            name = "add",
            params = listOf(
                Param("a", TypeRef("Int")),
                Param("b", TypeRef("Int"))
            ),
            returnType = TypeRef("Int"),
            contract = ContractNode(
                requires = listOf(
                    ContractClause(ExprNode.Apply(ExprNode.Ref(">="), listOf(ExprNode.Ref("a"), ExprNode.Literal(LiteralValue.IntLit(0)))))
                ),
                ensures = listOf(
                    ContractClause(ExprNode.Apply(ExprNode.Ref(">="), listOf(ExprNode.Ref("result"), ExprNode.Ref("a"))))
                )
            ),
            effects = emptySet(),
            body = ExprNode.Apply(ExprNode.Ref("+"), listOf(ExprNode.Ref("a"), ExprNode.Ref("b")))
        )

        val sig = extractor.extractSignature(fn)
        assertEquals(listOf("Int", "Int"), sig.inputTypes)
        assertEquals("Int", sig.outputType)
        assertTrue(sig.effects.isEmpty())
        assertEquals(1, sig.requiresPredicates.size)
        assertEquals(1, sig.ensuresPredicates.size)
        assertTrue(sig.requiresPredicates[0].contains(">="))
        assertTrue(sig.ensuresPredicates[0].contains("result"))
    }

    @Test
    fun `extract signature from FnDef with effects`() {
        val fn = FnDef(
            name = "readFile",
            params = listOf(Param("path", TypeRef("String"))),
            returnType = TypeRef("Bytes"),
            effects = setOf("IO"),
            body = ExprNode.Ref("builtin_read")
        )

        val sig = extractor.extractSignature(fn)
        assertEquals(listOf("String"), sig.inputTypes)
        assertEquals("Bytes", sig.outputType)
        assertEquals(listOf("IO"), sig.effects)
    }

    @Test
    fun `extract signature from TypeDef`() {
        val td = TypeDef(
            name = "Option",
            typeParams = listOf(TypeVar("T")),
            variants = listOf(
                Variant("Some", listOf(Param("value", TypeRef("T")))),
                Variant("None", emptyList())
            )
        )

        val sig = extractor.extractSignature(td)
        assertEquals("Option", sig.outputType)
        assertEquals(2, sig.ensuresPredicates.size)
        assertTrue(sig.ensuresPredicates[0].contains("Some"))
        assertTrue(sig.ensuresPredicates[1].contains("None"))
    }

    @Test
    fun `extract signature from TraitDef`() {
        val td = TraitDef(
            name = "Eq",
            methods = listOf(
                FnSignature("eq", listOf(Param("self", TypeRef("Self")), Param("other", TypeRef("Self"))), TypeRef("Bool")),
                FnSignature("neq", listOf(Param("self", TypeRef("Self")), Param("other", TypeRef("Self"))), TypeRef("Bool"))
            ),
            laws = emptyList()
        )

        val sig = extractor.extractSignature(td)
        assertEquals("Eq", sig.outputType)
        assertEquals(2, sig.ensuresPredicates.size)
        assertTrue(sig.ensuresPredicates[0].contains("eq("))
        assertTrue(sig.ensuresPredicates[1].contains("neq("))
    }

    @Test
    fun `extract signature with generic TypeRef`() {
        val fn = FnDef(
            name = "map",
            params = listOf(
                Param("list", TypeRef("List", listOf(TypeRef("A")))),
                Param("f", TypeRef("Fn", listOf(TypeRef("A"), TypeRef("B"))))
            ),
            returnType = TypeRef("List", listOf(TypeRef("B"))),
            body = ExprNode.Ref("builtin_map")
        )

        val sig = extractor.extractSignature(fn)
        assertEquals(listOf("List<A>", "Fn<A, B>"), sig.inputTypes)
        assertEquals("List<B>", sig.outputType)
    }

    // -- Search tests --

    private fun makeNode(
        hash: Hash,
        nodeType: String = "fn",
        sig: SemanticSignature? = null,
        aliases: List<String> = emptyList(),
        intent: String? = null,
        domainTags: List<String> = emptyList(),
        verificationTier: Int = 0
    ): RegistryNode {
        val verification = if (verificationTier > 0) {
            listOf(VerificationRecord(tier = verificationTier, tool = "test", timestamp = 0L))
        } else emptyList()
        return RegistryNode(
            hash = hash,
            nodeType = nodeType,
            ast = "{}",
            semanticSignature = sig,
            metadata = NodeMetadata(
                aliases = aliases,
                intent = intent,
                createdAt = System.currentTimeMillis(),
                domainTags = domainTags
            ),
            verification = verification
        )
    }

    @Test
    fun `search by input and output types`() = runTest {
        val store = InMemoryStore()
        val search = SemanticSearch(store)

        store.store(makeNode("h1", sig = SemanticSignature(listOf("Int", "Int"), "Int"), aliases = listOf("add")))
        store.store(makeNode("h2", sig = SemanticSignature(listOf("String"), "Int"), aliases = listOf("strlen")))
        store.store(makeNode("h3", sig = SemanticSignature(listOf("Int", "Int"), "Bool"), aliases = listOf("eq")))

        val results = search.search(SearchQuery(inputTypes = listOf("Int", "Int"), outputType = "Int"))
        assertTrue(results.isNotEmpty())
        assertEquals("h1", results[0].hash)
    }

    @Test
    fun `search by effects - pure`() = runTest {
        val store = InMemoryStore()
        val search = SemanticSearch(store)

        store.store(makeNode("pure1", sig = SemanticSignature(listOf("Int"), "Int", effects = emptyList()), aliases = listOf("inc")))
        store.store(makeNode("io1", sig = SemanticSignature(listOf("String"), "Bytes", effects = listOf("IO")), aliases = listOf("read")))

        val results = search.search(SearchQuery(effects = "pure"))
        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.semanticSignature?.effects?.isEmpty() == true })
    }

    @Test
    fun `search by effects - IO`() = runTest {
        val store = InMemoryStore()
        val search = SemanticSearch(store)

        store.store(makeNode("pure1", sig = SemanticSignature(listOf("Int"), "Int"), aliases = listOf("inc")))
        store.store(makeNode("io1", sig = SemanticSignature(listOf("String"), "Bytes", effects = listOf("IO")), aliases = listOf("read")))

        val results = search.search(SearchQuery(effects = "IO"))
        assertTrue(results.isNotEmpty())
        assertEquals("io1", results[0].hash)
    }

    @Test
    fun `search by contract predicates`() = runTest {
        val store = InMemoryStore()
        val search = SemanticSearch(store)

        store.store(makeNode("h1", sig = SemanticSignature(
            listOf("Int", "Int"), "Int",
            ensuresPredicates = listOf("result >= a", "result >= b")
        ), aliases = listOf("max")))
        store.store(makeNode("h2", sig = SemanticSignature(listOf("Int", "Int"), "Int"), aliases = listOf("add")))

        val results = search.search(SearchQuery(outputType = "Int", ensuresContains = listOf("result >= a")))
        assertTrue(results.isNotEmpty())
        assertEquals("h1", results[0].hash)
    }

    @Test
    fun `search by domain tags`() = runTest {
        val store = InMemoryStore()
        val search = SemanticSearch(store)

        store.store(makeNode("h1", sig = SemanticSignature(listOf("Int"), "Int"), domainTags = listOf("math", "core")))
        store.store(makeNode("h2", sig = SemanticSignature(listOf("String"), "String"), domainTags = listOf("text")))

        val results = search.search(SearchQuery(domainTags = listOf("math")))
        assertTrue(results.isNotEmpty())
        assertEquals("h1", results[0].hash)
    }

    @Test
    fun `search by text query`() = runTest {
        val store = InMemoryStore()
        val search = SemanticSearch(store)

        store.store(makeNode("h1", sig = SemanticSignature(listOf("Int", "Int"), "Int"), aliases = listOf("add"), intent = "Add two integers"))
        store.store(makeNode("h2", sig = SemanticSignature(listOf("String"), "Int"), aliases = listOf("strlen"), intent = "Get string length"))

        val results = search.search(SearchQuery(textQuery = "add"))
        assertTrue(results.isNotEmpty())
        assertEquals("h1", results[0].hash)
    }

    @Test
    fun `verification tier filtering`() = runTest {
        val store = InMemoryStore()
        val search = SemanticSearch(store)

        store.store(makeNode("h1", sig = SemanticSignature(listOf("Int"), "Int"), verificationTier = 3))
        store.store(makeNode("h2", sig = SemanticSignature(listOf("Int"), "Int"), verificationTier = 1))
        store.store(makeNode("h3", sig = SemanticSignature(listOf("Int"), "Int"), verificationTier = 0))

        val results = search.search(SearchQuery(outputType = "Int", minVerificationTier = 2))
        assertEquals(1, results.size)
        assertEquals("h1", results[0].hash)
    }

    @Test
    fun `scoring ranks exact matches higher`() = runTest {
        val store = InMemoryStore()
        val search = SemanticSearch(store)

        store.store(makeNode("exact", sig = SemanticSignature(listOf("Int", "Int"), "Int"), domainTags = listOf("math"), verificationTier = 3))
        store.store(makeNode("partial", sig = SemanticSignature(listOf("Int"), "Int"), domainTags = listOf("math")))

        val results = search.search(SearchQuery(inputTypes = listOf("Int", "Int"), outputType = "Int", domainTags = listOf("math")))
        assertTrue(results.size >= 1)
        assertEquals("exact", results[0].hash)
    }
}
