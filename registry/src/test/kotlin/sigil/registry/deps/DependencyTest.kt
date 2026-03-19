package sigil.registry.deps

import kotlinx.coroutines.test.runTest
import sigil.ast.*
import sigil.registry.store.InMemoryStore
import sigil.registry.store.NodeMetadata
import sigil.registry.store.RegistryNode
import kotlin.test.*

class DependencyTest {

    private lateinit var store: InMemoryStore
    private lateinit var tracker: DependencyTracker

    private fun testNode(
        hash: String,
        dependencies: List<String> = emptyList()
    ) = RegistryNode(
        hash = hash,
        nodeType = "FnDef",
        ast = """{"type":"FnDef","name":"test"}""",
        semanticSignature = null,
        metadata = NodeMetadata(
            createdAt = System.currentTimeMillis()
        ),
        dependencies = dependencies
    )

    @BeforeTest
    fun setup() {
        store = InMemoryStore()
        tracker = DependencyTracker(store)
    }

    @Test
    fun `extractDependencies from FnDef walks expression tree`() {
        val fn = FnDef(
            name = "myFn",
            params = listOf(Param("x", TypeRef("#sigil:int"))),
            returnType = TypeRef("#sigil:int"),
            body = ExprNode.Apply(
                fn = ExprNode.Ref("abc123def"),
                args = listOf(
                    ExprNode.Ref("def456abc"),
                    ExprNode.Literal(LiteralValue.IntLit(42))
                )
            )
        )
        val deps = tracker.extractDependencies(fn)
        assertEquals(setOf("abc123def", "def456abc"), deps)
    }

    @Test
    fun `extractDependencies excludes sigil built-in refs`() {
        val fn = FnDef(
            name = "myFn",
            params = emptyList(),
            returnType = TypeRef("#sigil:int"),
            body = ExprNode.Apply(
                fn = ExprNode.Ref("#sigil:add"),
                args = listOf(
                    ExprNode.Ref("abc123"),
                    ExprNode.Literal(LiteralValue.IntLit(1))
                )
            )
        )
        val deps = tracker.extractDependencies(fn)
        assertEquals(setOf("abc123"), deps)
    }

    @Test
    fun `extractDependencies from ModuleDef`() {
        val module = ModuleDef(
            name = "myModule",
            exports = listOf("abc123", "def456"),
            definitions = listOf("abc123", "ghi789")
        )
        val deps = tracker.extractDependencies(module)
        assertEquals(setOf("abc123", "def456", "ghi789"), deps)
    }

    @Test
    fun `extractDependencies walks nested expressions`() {
        val fn = FnDef(
            name = "complex",
            params = emptyList(),
            returnType = TypeRef("#sigil:int"),
            body = ExprNode.Let(
                binding = "x",
                value = ExprNode.Ref("hash_a"),
                body = ExprNode.If(
                    cond = ExprNode.Ref("hash_b"),
                    then_ = ExprNode.Match(
                        scrutinee = ExprNode.Ref("hash_c"),
                        arms = listOf(
                            MatchArm(
                                pattern = Pattern.WildcardPattern,
                                body = ExprNode.Ref("hash_d")
                            )
                        )
                    ),
                    else_ = ExprNode.Block(
                        exprs = listOf(
                            ExprNode.Ref("hash_e"),
                            ExprNode.Lambda(
                                params = listOf(Param("y", TypeRef("#sigil:int"))),
                                body = ExprNode.Ref("hash_f")
                            )
                        )
                    )
                )
            )
        )
        val deps = tracker.extractDependencies(fn)
        assertEquals(setOf("hash_a", "hash_b", "hash_c", "hash_d", "hash_e", "hash_f"), deps)
    }

    @Test
    fun `registerDependencies updates store`() = runTest {
        val nodeC = testNode("hash_c")
        val nodeB = testNode("hash_b")
        val nodeA = testNode("hash_a")
        store.store(nodeC)
        store.store(nodeB)
        store.store(nodeA)

        tracker.registerDependencies("hash_a", setOf("hash_b", "hash_c"))

        val updatedA = store.get("hash_a")!!
        assertTrue(updatedA.dependencies.containsAll(listOf("hash_b", "hash_c")))

        val dependentsOfB = store.getDependents("hash_b")
        assertTrue("hash_a" in dependentsOfB)

        val dependentsOfC = store.getDependents("hash_c")
        assertTrue("hash_a" in dependentsOfC)
    }

    @Test
    fun `getTransitiveDependents follows chain`() = runTest {
        // C -> B -> A (A depends on B, B depends on C)
        val nodeC = testNode("hash_c")
        val nodeB = testNode("hash_b")
        val nodeA = testNode("hash_a")
        store.store(nodeC)
        store.store(nodeB)
        store.store(nodeA)

        tracker.registerDependencies("hash_b", setOf("hash_c"))
        tracker.registerDependencies("hash_a", setOf("hash_b"))

        val transitiveDependentsOfC = tracker.getTransitiveDependents("hash_c")
        assertEquals(setOf("hash_b", "hash_a"), transitiveDependentsOfC)
    }

    @Test
    fun `getTransitiveDependencies follows chain`() = runTest {
        val nodeC = testNode("hash_c")
        val nodeB = testNode("hash_b")
        val nodeA = testNode("hash_a")
        store.store(nodeC)
        store.store(nodeB)
        store.store(nodeA)

        tracker.registerDependencies("hash_b", setOf("hash_c"))
        tracker.registerDependencies("hash_a", setOf("hash_b"))

        val transitiveDepsOfA = tracker.getTransitiveDependencies("hash_a")
        assertEquals(setOf("hash_b", "hash_c"), transitiveDepsOfA)
    }

    @Test
    fun `cascadeDeprecation finds all affected nodes`() = runTest {
        val nodeC = testNode("hash_c")
        val nodeB = testNode("hash_b")
        val nodeA = testNode("hash_a")
        store.store(nodeC)
        store.store(nodeB)
        store.store(nodeA)

        tracker.registerDependencies("hash_b", setOf("hash_c"))
        tracker.registerDependencies("hash_a", setOf("hash_b"))

        val result = tracker.cascadeDeprecation("hash_c")
        assertEquals("hash_c", result.deprecatedHash)
        assertEquals(setOf("hash_b", "hash_a"), result.affectedNodes)
        assertEquals(setOf("hash_b", "hash_a"), result.reverificationNeeded)
    }

    @Test
    fun `wouldCreateCycle always returns false for content-addressed hashes`() = runTest {
        val nodeA = testNode("hash_a")
        val nodeB = testNode("hash_b")
        store.store(nodeA)
        store.store(nodeB)

        assertFalse(tracker.wouldCreateCycle("hash_a", "hash_b"))
        assertFalse(tracker.wouldCreateCycle("hash_b", "hash_a"))
    }

    @Test
    fun `diamond dependency graph`() = runTest {
        //   A
        //  / \
        // B   C
        //  \ /
        //   D
        val nodeD = testNode("hash_d")
        val nodeC = testNode("hash_c")
        val nodeB = testNode("hash_b")
        val nodeA = testNode("hash_a")
        store.store(nodeD)
        store.store(nodeC)
        store.store(nodeB)
        store.store(nodeA)

        tracker.registerDependencies("hash_b", setOf("hash_d"))
        tracker.registerDependencies("hash_c", setOf("hash_d"))
        tracker.registerDependencies("hash_a", setOf("hash_b", "hash_c"))

        val transitiveDependentsOfD = tracker.getTransitiveDependents("hash_d")
        assertEquals(setOf("hash_b", "hash_c", "hash_a"), transitiveDependentsOfD)

        val transitiveDepsOfA = tracker.getTransitiveDependencies("hash_a")
        assertEquals(setOf("hash_b", "hash_c", "hash_d"), transitiveDepsOfA)
    }
}
