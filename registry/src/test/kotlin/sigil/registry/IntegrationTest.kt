package sigil.registry

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import sigil.ast.*
import sigil.hash.Hasher
import sigil.parser.parse
import sigil.registry.api.*
import sigil.registry.deps.DependencyTracker
import sigil.registry.semantic.SearchQuery
import sigil.registry.semantic.SemanticExtractor
import sigil.registry.semantic.SemanticSearch
import sigil.registry.smt.SimpleSmtSolver
import sigil.registry.smt.SmtEncoder
import sigil.registry.smt.SmtResult
import sigil.registry.store.InMemoryStore
import sigil.registry.store.NodeMetadata
import sigil.registry.store.RegistryNode
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IntegrationTest {

    private lateinit var store: InMemoryStore
    private lateinit var service: RegistryService

    @BeforeEach
    fun setup() {
        store = InMemoryStore()
        service = RegistryService(store)
    }

    // --- 1. Publish and retrieve ---

    @Test
    fun `publish a function and retrieve it by hash`() = runTest {
        val source = "fn add(x: Int, y: Int) -> Int { x + y }"
        val responses = service.publish(PublishRequest(source = source))

        assertEquals(1, responses.size)
        val response = responses[0]
        assertEquals("FnDef", response.nodeType)
        assertTrue(response.hash.isNotBlank())
        assertTrue(response.verificationTier >= 1)
        assertTrue(response.verificationDetails.isNotEmpty())

        val node = service.getNode(response.hash)
        assertNotNull(node)
        assertEquals(response.hash, node.hash)
        assertEquals("FnDef", node.nodeType)
        assertTrue(node.ast.contains("add"))
        assertNotNull(node.semanticSignature)
    }

    @Test
    fun `published function preserves semantic signature`() = runTest {
        val source = "fn multiply(a: Int, b: Int) -> Int { a * b }"
        val responses = service.publish(PublishRequest(source = source))
        val node = service.getNode(responses[0].hash)
        assertNotNull(node)

        val sig = node.semanticSignature
        assertNotNull(sig)
        assertEquals(2, sig.inputTypes.size)
        assertEquals(PrimitiveTypes.INT, sig.outputType)
    }

    @Test
    fun `publish function with contracts gets tier 2`() = runTest {
        val source = """
            fn positive(x: Int) -> Int {
                requires x > 0
                x
            }
        """.trimIndent()
        val responses = service.publish(PublishRequest(source = source))
        assertEquals(1, responses.size)
        assertEquals(2, responses[0].verificationTier)
    }

    // --- 2. Semantic search ---

    @Test
    fun `search by output type finds matching functions`() = runTest {
        service.publish(PublishRequest(source = "fn toInt(x: Int) -> Int { x }"))
        service.publish(PublishRequest(source = "fn toBool(x: Int) -> Bool { x > 0 }"))

        val search = SemanticSearch(store)
        val intResults = search.search(SearchQuery(outputType = PrimitiveTypes.INT))
        assertTrue(intResults.isNotEmpty())
        assertTrue(intResults.all { it.semanticSignature?.outputType == PrimitiveTypes.INT })

        val boolResults = search.search(SearchQuery(outputType = PrimitiveTypes.BOOL))
        assertTrue(boolResults.isNotEmpty())
        assertTrue(boolResults.all { it.semanticSignature?.outputType == PrimitiveTypes.BOOL })
    }

    @Test
    fun `search by input types finds matching functions`() = runTest {
        service.publish(PublishRequest(source = "fn unary(x: Int) -> Int { x }"))
        service.publish(PublishRequest(source = "fn binary(x: Int, y: Int) -> Int { x + y }"))

        val search = SemanticSearch(store)
        val results = search.search(SearchQuery(
            inputTypes = listOf(PrimitiveTypes.INT, PrimitiveTypes.INT),
            outputType = PrimitiveTypes.INT
        ))
        assertTrue(results.isNotEmpty())
        // The binary function should score higher since it matches 2 input types
        val topResult = results[0]
        assertEquals(2, topResult.semanticSignature?.inputTypes?.size)
    }

    @Test
    fun `search by text query finds function by alias`() = runTest {
        service.publish(PublishRequest(
            source = "fn add(x: Int, y: Int) -> Int { x + y }",
            metadata = PublishMetadata(aliases = listOf("addition", "sum"))
        ))
        service.publish(PublishRequest(source = "fn sub(x: Int, y: Int) -> Int { x - y }"))

        val results = service.search(SearchRequest(textQuery = "addition"))
        assertTrue(results.isNotEmpty())
        assertTrue(results[0].metadata.aliases.contains("addition"))
    }

    @Test
    fun `search by ensures predicates finds contracted functions`() = runTest {
        val source = """
            fn checked(x: Int) -> Int {
                requires x > 0
                x
            }
        """.trimIndent()
        service.publish(PublishRequest(source = source))
        service.publish(PublishRequest(source = "fn id(x: Int) -> Int { x }"))

        val search = SemanticSearch(store)
        // The requires predicate will be serialized and stored in requiresPredicates
        // Search for nodes that have contract-related info by text query
        val results = search.search(SearchQuery(textQuery = "checked"))
        assertTrue(results.isNotEmpty())
        val checkedResult = results.find { it.metadata.aliases.contains("checked") }
        assertNotNull(checkedResult)
        // Verify the semantic signature has requires predicates
        assertNotNull(checkedResult.semanticSignature)
        assertTrue(checkedResult.semanticSignature!!.requiresPredicates.isNotEmpty())
    }

    // --- 3. Contract chaining ---

    @Test
    fun `contract chaining validates sort then binary_search composition`() = runTest {
        // Build ASTs directly for sort and binary_search with contracts
        val sortFn = FnDef(
            name = "sort",
            params = listOf(Param("xs", TypeRef(PrimitiveTypes.INT))),
            returnType = TypeRef(PrimitiveTypes.INT),
            contract = ContractNode(
                requires = emptyList(),
                ensures = listOf(ContractClause(
                    ExprNode.Apply(ExprNode.Ref("#sigil:gte"), listOf(ExprNode.Ref("result"), ExprNode.Literal(LiteralValue.IntLit(0))))
                ))
            ),
            effects = emptySet(),
            body = ExprNode.Ref("xs")
        )

        val bsearchFn = FnDef(
            name = "binary_search",
            params = listOf(Param("xs", TypeRef(PrimitiveTypes.INT))),
            returnType = TypeRef(PrimitiveTypes.INT),
            contract = ContractNode(
                requires = listOf(ContractClause(
                    ExprNode.Apply(ExprNode.Ref("#sigil:gte"), listOf(ExprNode.Ref("result"), ExprNode.Literal(LiteralValue.IntLit(0))))
                )),
                ensures = emptyList()
            ),
            effects = emptySet(),
            body = ExprNode.Ref("xs")
        )

        val solver = SimpleSmtSolver()
        val result = solver.verifyChaining(sortFn, bsearchFn)
        // sort ensures result >= 0, binary_search requires result >= 0
        // The chaining should be valid (UNSAT means the negated requires is unsatisfiable given ensures)
        assertEquals(SmtResult.UNSAT, result.result)
        assertTrue(result.provedClauses.contains("requires[0]"))
    }

    @Test
    fun `contract chaining detects invalid composition`() = runTest {
        val fnA = FnDef(
            name = "fnA",
            params = listOf(Param("x", TypeRef(PrimitiveTypes.INT))),
            returnType = TypeRef(PrimitiveTypes.INT),
            contract = ContractNode(
                requires = emptyList(),
                ensures = listOf(ContractClause(
                    ExprNode.Apply(ExprNode.Ref("#sigil:gte"), listOf(ExprNode.Ref("result"), ExprNode.Literal(LiteralValue.IntLit(0))))
                ))
            ),
            effects = emptySet(),
            body = ExprNode.Ref("x")
        )

        // fnB requires result > 5, which is NOT guaranteed by fnA's ensures (result >= 0)
        val fnB = FnDef(
            name = "fnB",
            params = listOf(Param("x", TypeRef(PrimitiveTypes.INT))),
            returnType = TypeRef(PrimitiveTypes.INT),
            contract = ContractNode(
                requires = listOf(ContractClause(
                    ExprNode.Apply(ExprNode.Ref("#sigil:gt"), listOf(ExprNode.Ref("result"), ExprNode.Literal(LiteralValue.IntLit(5))))
                )),
                ensures = emptyList()
            ),
            effects = emptySet(),
            body = ExprNode.Ref("x")
        )

        val solver = SimpleSmtSolver()
        val result = solver.verifyChaining(fnA, fnB)
        // result >= 0 does NOT imply result > 5, so this should NOT be UNSAT
        assertTrue(result.result != SmtResult.UNSAT)
    }

    // --- 4. Dependency tracking ---

    @Test
    fun `dependency graph tracks references between functions`() = runTest {
        // Publish function A
        val sourceA = "fn helperA(x: Int) -> Int { x + 1 }"
        val responsesA = service.publish(PublishRequest(source = sourceA))
        val hashA = responsesA[0].hash

        // Publish function B that references A's hash in its body
        val parsed = parse("fn callerB(x: Int) -> Int { x }") as List<Any>
        val fnB = (parsed[0] as FnDef).let { fn ->
            // Replace body with a reference to hashA
            fn.copy(body = ExprNode.Apply(ExprNode.Ref(hashA), listOf(ExprNode.Ref("x"))))
        }

        // Manually publish fnB through the store
        val hashB = Hasher.hashFnDef(fnB)
        val depTracker = DependencyTracker(store)
        val deps = depTracker.extractDependencies(fnB)
        assertTrue(deps.contains(hashA))

        val extractor = SemanticExtractor()
        val sig = extractor.extractSignature(fnB)
        val now = System.currentTimeMillis()
        val node = RegistryNode(
            hash = hashB,
            nodeType = "FnDef",
            ast = kotlinx.serialization.json.Json.encodeToString(FnDef.serializer(), fnB),
            semanticSignature = sig,
            metadata = NodeMetadata(aliases = listOf("callerB"), createdAt = now),
            verification = emptyList(),
            dependencies = deps.toList()
        )
        store.store(node)
        depTracker.registerDependencies(hashB, deps)

        // Verify dependency graph
        val dependents = store.getDependents(hashA)
        assertTrue(dependents.contains(hashB))

        val dependencies = store.getDependencies(hashB)
        assertTrue(dependencies.contains(hashA))
    }

    @Test
    fun `deprecation cascades to dependents`() = runTest {
        val sourceA = "fn base(x: Int) -> Int { x }"
        val responsesA = service.publish(PublishRequest(source = sourceA))
        val hashA = responsesA[0].hash

        // Create function B that depends on A
        val fnB = FnDef(
            name = "dependent",
            params = listOf(Param("x", TypeRef(PrimitiveTypes.INT))),
            returnType = TypeRef(PrimitiveTypes.INT),
            contract = null,
            effects = emptySet(),
            body = ExprNode.Apply(ExprNode.Ref(hashA), listOf(ExprNode.Ref("x")))
        )

        val hashB = Hasher.hashFnDef(fnB)
        val now = System.currentTimeMillis()
        val nodeB = RegistryNode(
            hash = hashB,
            nodeType = "FnDef",
            ast = kotlinx.serialization.json.Json.encodeToString(FnDef.serializer(), fnB),
            semanticSignature = SemanticExtractor().extractSignature(fnB),
            metadata = NodeMetadata(aliases = listOf("dependent"), createdAt = now),
            verification = emptyList(),
            dependencies = listOf(hashA)
        )
        store.store(nodeB)
        val depTracker = DependencyTracker(store)
        depTracker.registerDependencies(hashB, setOf(hashA))

        // Deprecate A
        val result = service.deprecate(hashA)
        assertEquals(hashA, result.deprecatedHash)
        assertTrue(result.affectedNodes.contains(hashB))
        assertTrue(result.reverificationNeeded.contains(hashB))
    }

    // --- 5. Duplicate detection ---

    @Test
    fun `same function body with different names produces same hash`() = runTest {
        val source1 = "fn add(x: Int, y: Int) -> Int { x + y }"
        val source2 = "fn plus(x: Int, y: Int) -> Int { x + y }"

        val responses1 = service.publish(PublishRequest(source = source1))
        val responses2 = service.publish(PublishRequest(source = source2))

        // Content-addressed hashing excludes the function name,
        // so identical bodies with same params/types should hash the same
        assertEquals(responses1[0].hash, responses2[0].hash)
    }

    @Test
    fun `different function bodies produce different hashes`() = runTest {
        val source1 = "fn f1(x: Int) -> Int { x + 1 }"
        val source2 = "fn f2(x: Int) -> Int { x + 2 }"

        val responses1 = service.publish(PublishRequest(source = source1))
        val responses2 = service.publish(PublishRequest(source = source2))

        assertTrue(responses1[0].hash != responses2[0].hash)
    }

    // --- 6. Full workflow simulation ---

    @Test
    fun `full workflow - publish, search, compose, verify dependency chain`() = runTest {
        // Step 1: Publish a math utility function
        val absSource = """
            fn abs(x: Int) -> Int {
                requires x >= 0
                x
            }
        """.trimIndent()
        val absResponses = service.publish(PublishRequest(
            source = absSource,
            metadata = PublishMetadata(
                aliases = listOf("absolute_value"),
                intent = "returns the absolute value of an integer",
                domainTags = listOf("math")
            )
        ))
        val absHash = absResponses[0].hash
        assertEquals(2, absResponses[0].verificationTier) // has contract

        // Step 2: Search for it by type signature
        val searchResults = service.search(SearchRequest(
            inputTypes = listOf(PrimitiveTypes.INT),
            outputType = PrimitiveTypes.INT
        ))
        assertTrue(searchResults.isNotEmpty())
        val foundAbs = searchResults.find { it.hash == absHash }
        assertNotNull(foundAbs)

        // Step 3: Search by domain tag
        val tagResults = service.search(SearchRequest(domainTags = listOf("math")))
        assertTrue(tagResults.isNotEmpty())
        assertTrue(tagResults.any { it.hash == absHash })

        // Step 4: Search by text query
        val textResults = service.search(SearchRequest(textQuery = "absolute_value"))
        assertTrue(textResults.isNotEmpty())
        assertTrue(textResults.any { it.hash == absHash })

        // Step 5: Publish a function that depends on abs
        val composedFn = FnDef(
            name = "abs_diff",
            params = listOf(
                Param("a", TypeRef(PrimitiveTypes.INT)),
                Param("b", TypeRef(PrimitiveTypes.INT))
            ),
            returnType = TypeRef(PrimitiveTypes.INT),
            contract = null,
            effects = emptySet(),
            body = ExprNode.Apply(
                ExprNode.Ref(absHash),
                listOf(ExprNode.Apply(
                    ExprNode.Ref("#sigil:sub"),
                    listOf(ExprNode.Ref("a"), ExprNode.Ref("b"))
                ))
            )
        )
        val composedHash = Hasher.hashFnDef(composedFn)
        val composedExtractor = SemanticExtractor()
        val now = System.currentTimeMillis()
        val composedNode = RegistryNode(
            hash = composedHash,
            nodeType = "FnDef",
            ast = kotlinx.serialization.json.Json.encodeToString(FnDef.serializer(), composedFn),
            semanticSignature = composedExtractor.extractSignature(composedFn),
            metadata = NodeMetadata(
                aliases = listOf("abs_diff"),
                intent = "absolute difference",
                createdAt = now,
                domainTags = listOf("math")
            ),
            verification = emptyList(),
            dependencies = listOf(absHash)
        )
        store.store(composedNode)

        val depTracker = DependencyTracker(store)
        depTracker.registerDependencies(composedHash, setOf(absHash))

        // Step 6: Verify the dependency chain
        val dependents = store.getDependents(absHash)
        assertTrue(dependents.contains(composedHash))

        val dependencies = store.getDependencies(composedHash)
        assertTrue(dependencies.contains(absHash))

        // Step 7: Deprecate abs and verify cascade
        val deprecation = service.deprecate(absHash)
        assertTrue(deprecation.affectedNodes.contains(composedHash))
        assertTrue(deprecation.reverificationNeeded.contains(composedHash))

        // Step 8: Verify stats reflect everything
        val stats = service.getStats()
        assertEquals(2, stats.totalNodes)
    }

    @Test
    fun `smt encoder roundtrip for contract verification`() = runTest {
        val source = """
            fn positive(x: Int) -> Int {
                requires x > 0
                x
            }
        """.trimIndent()
        val responses = service.publish(PublishRequest(source = source))
        val hash = responses[0].hash
        val node = service.getNode(hash)
        assertNotNull(node)

        // Deserialize the stored AST and verify SMT encoding works
        val fn = kotlinx.serialization.json.Json.decodeFromString(FnDef.serializer(), node.ast)
        assertNotNull(fn.contract)
        assertEquals(1, fn.contract!!.requires.size)

        val encoder = SmtEncoder()
        val smtProgram = encoder.encodeContract(fn)
        assertTrue(smtProgram.contains("check-sat"))

        // Test with a manually constructed function where ensures can be proven by SimpleSmtSolver
        // requires x > 0, ensures x >= 0 (which is implied by x > 0)
        val fnWithEnsures = FnDef(
            name = "test",
            params = listOf(Param("x", TypeRef(PrimitiveTypes.INT))),
            returnType = TypeRef(PrimitiveTypes.INT),
            contract = ContractNode(
                requires = listOf(ContractClause(
                    ExprNode.Apply(ExprNode.Ref("#sigil:gt"), listOf(ExprNode.Ref("x"), ExprNode.Literal(LiteralValue.IntLit(0))))
                )),
                ensures = listOf(ContractClause(
                    ExprNode.Apply(ExprNode.Ref("#sigil:gte"), listOf(ExprNode.Ref("x"), ExprNode.Literal(LiteralValue.IntLit(0))))
                ))
            ),
            effects = emptySet(),
            body = ExprNode.Ref("x")
        )

        val solver = SimpleSmtSolver()
        val verifyResult = solver.verifyContracts(fnWithEnsures)
        // x > 0 implies x >= 0, so the solver should prove ensures[0]
        assertTrue(verifyResult.provedClauses.contains("ensures[0]"))
    }
}
