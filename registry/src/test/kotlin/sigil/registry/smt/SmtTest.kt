package sigil.registry.smt

import kotlinx.coroutines.test.runTest
import sigil.ast.*
import kotlin.test.*

class SmtTest {

    private val encoder = SmtEncoder()
    private val solver = SimpleSmtSolver()

    // Helper to build a simple comparison expression: (op lhs rhs)
    private fun compare(op: String, lhs: ExprNode, rhs: ExprNode): ExprNode =
        ExprNode.Apply(ExprNode.Ref(op), listOf(lhs, rhs))

    private fun intLit(n: Long): ExprNode = ExprNode.Literal(LiteralValue.IntLit(n))
    private fun boolLit(b: Boolean): ExprNode = ExprNode.Literal(LiteralValue.BoolLit(b))
    private fun ref(name: String): ExprNode = ExprNode.Ref(name)

    private fun intType(): TypeRef = TypeRef(PrimitiveTypes.INT)
    private fun boolType(): TypeRef = TypeRef(PrimitiveTypes.BOOL)

    private fun makeFn(
        name: String,
        params: List<Param>,
        returnType: TypeRef = intType(),
        requires: List<ExprNode> = emptyList(),
        ensures: List<ExprNode> = emptyList(),
        body: ExprNode = intLit(0)
    ): FnDef = FnDef(
        name = name,
        params = params,
        returnType = returnType,
        contract = if (requires.isEmpty() && ensures.isEmpty()) null else ContractNode(
            requires = requires.map { ContractClause(it) },
            ensures = ensures.map { ContractClause(it) }
        ),
        body = body
    )

    // --- Encoder Tests ---

    @Test
    fun `encode simple contract to SMT-LIB2`() {
        // fn abs(x: Int) requires x > 0 ensures result > 0
        val fn = makeFn(
            name = "abs",
            params = listOf(Param("x", intType())),
            requires = listOf(compare("#sigil:gt", ref("x"), intLit(0))),
            ensures = listOf(compare("#sigil:gt", ref("result"), intLit(0)))
        )

        val smt = encoder.encodeContract(fn)

        assertTrue(smt.contains("(set-logic QF_LIA)"))
        assertTrue(smt.contains("(declare-const x Int)"))
        assertTrue(smt.contains("(declare-const result Int)"))
        assertTrue(smt.contains("(assert (> x 0))"))
        assertTrue(smt.contains("(assert (not (> result 0)))"))
        assertTrue(smt.contains("(check-sat)"))
    }

    @Test
    fun `encode arithmetic operators`() {
        val expr = compare("#sigil:add", ref("x"), intLit(1))
        val encoded = encoder.encodeExpr(expr)
        assertEquals("(+ x 1)", encoded)
    }

    @Test
    fun `encode boolean operators`() {
        val expr = ExprNode.Apply(
            ExprNode.Ref("#sigil:and"),
            listOf(
                compare("#sigil:gt", ref("x"), intLit(0)),
                compare("#sigil:lt", ref("x"), intLit(100))
            )
        )
        val encoded = encoder.encodeExpr(expr)
        assertEquals("(and (> x 0) (< x 100))", encoded)
    }

    @Test
    fun `encode not operator`() {
        val expr = ExprNode.Apply(
            ExprNode.Ref("#sigil:not"),
            listOf(compare("#sigil:eq", ref("x"), intLit(0)))
        )
        val encoded = encoder.encodeExpr(expr)
        assertEquals("(not (= x 0))", encoded)
    }

    @Test
    fun `encode if expression as ite`() {
        val expr = ExprNode.If(
            cond = compare("#sigil:gt", ref("x"), intLit(0)),
            then_ = ref("x"),
            else_ = intLit(0)
        )
        val encoded = encoder.encodeExpr(expr)
        assertEquals("(ite (> x 0) x 0)", encoded)
    }

    @Test
    fun `encode let expression with substitution`() {
        val expr = ExprNode.Let(
            binding = "y",
            value = compare("#sigil:add", ref("x"), intLit(1)),
            body = compare("#sigil:gt", ExprNode.Ref("y"), intLit(0))
        )
        val encoded = encoder.encodeExpr(expr)
        assertEquals("(> (+ x 1) 0)", encoded)
    }

    @Test
    fun `encode function without contract`() {
        val fn = makeFn(
            name = "identity",
            params = listOf(Param("x", intType())),
        )
        val smt = encoder.encodeContract(fn)
        assertTrue(smt.contains("(declare-const x Int)"))
        assertTrue(smt.contains("(check-sat)"))
        assertFalse(smt.contains("(assert"))
    }

    @Test
    fun `encode chaining check`() {
        // sort ensures result is sorted (simplified: result >= 0)
        // binary_search requires input is sorted (simplified: x >= 0)
        val sortFn = makeFn(
            name = "sort",
            params = listOf(Param("x", intType())),
            ensures = listOf(compare("#sigil:gte", ref("result"), intLit(0)))
        )
        val searchFn = makeFn(
            name = "binary_search",
            params = listOf(Param("x", intType())),
            requires = listOf(compare("#sigil:gte", ref("result"), intLit(0)))
        )

        val smt = encoder.encodeChaining(sortFn, searchFn)
        assertTrue(smt.contains("chaining check"))
        assertTrue(smt.contains("(assert (>= result 0))"))
        assertTrue(smt.contains("(assert (not (>= result 0)))"))
        assertTrue(smt.contains("(check-sat)"))
    }

    // --- SimpleSmtSolver Tests ---

    @Test
    fun `verify x greater than 0 implies x not equal 0`() = runTest {
        // requires: x > 0, ensures: x != 0
        // x > 0 implies x != 0, so negating the ensures and combining with requires should be UNSAT
        val fn = makeFn(
            name = "positive_nonzero",
            params = listOf(Param("x", intType())),
            requires = listOf(compare("#sigil:gt", ref("x"), intLit(0))),
            ensures = listOf(
                ExprNode.Apply(ExprNode.Ref("#sigil:not"), listOf(compare("#sigil:eq", ref("x"), intLit(0))))
            )
        )

        val result = solver.verifyContracts(fn)
        assertEquals(SmtResult.UNSAT, result.result)
        assertTrue(result.provedClauses.contains("ensures[0]"))
        assertTrue(result.unprovedClauses.isEmpty())
    }

    @Test
    fun `verify basic UNSAT from direct contradiction`() = runTest {
        // assert (> x 0) and (not (> x 0)) => UNSAT
        val program = """
            (set-logic QF_LIA)
            (declare-const x Int)
            (assert (> x 0))
            (assert (not (> x 0)))
            (check-sat)
        """.trimIndent()

        val result = solver.verify(program)
        assertEquals(SmtResult.UNSAT, result)
    }

    @Test
    fun `verify contract chaining sort into binary_search`() = runTest {
        // sort ensures: result >= 0
        // binary_search requires: result >= 0
        // Chaining should be valid (UNSAT when negating requires given ensures)
        val sortFn = makeFn(
            name = "sort",
            params = listOf(Param("arr", intType())),
            ensures = listOf(compare("#sigil:gte", ref("result"), intLit(0)))
        )
        val searchFn = makeFn(
            name = "binary_search",
            params = listOf(Param("arr", intType())),
            requires = listOf(compare("#sigil:gte", ref("result"), intLit(0)))
        )

        val result = solver.verifyChaining(sortFn, searchFn)
        assertEquals(SmtResult.UNSAT, result.result)
        assertTrue(result.provedClauses.contains("requires[0]"))
    }

    @Test
    fun `handle unknown cases gracefully`() = runTest {
        // A lambda in the contract should result in UNKNOWN
        val fn = makeFn(
            name = "complex",
            params = listOf(Param("x", intType())),
            ensures = listOf(
                ExprNode.Lambda(
                    params = listOf(Param("y", intType())),
                    body = compare("#sigil:gt", ref("y"), intLit(0))
                )
            )
        )

        val result = solver.verifyContracts(fn)
        assertTrue(result.unprovedClauses.isNotEmpty())
    }

    @Test
    fun `SimpleSmtSolver returns UNKNOWN for complex programs`() = runTest {
        val program = """
            (set-logic QF_LIA)
            (declare-const x Int)
            (assert (> (+ x (* x x)) 0))
            (check-sat)
        """.trimIndent()

        val result = solver.verify(program)
        // This is too complex for SimpleSmtSolver - should return UNKNOWN or SAT
        assertTrue(result == SmtResult.UNKNOWN || result == SmtResult.SAT)
    }

    @Test
    fun `verify no contract returns SAT with empty lists`() = runTest {
        val fn = makeFn(
            name = "simple",
            params = listOf(Param("x", intType())),
        )

        val result = solver.verifyContracts(fn)
        assertEquals(SmtResult.SAT, result.result)
        assertTrue(result.provedClauses.isEmpty())
        assertTrue(result.unprovedClauses.isEmpty())
    }

    @Test
    fun `encode negative integer literal`() {
        val encoded = encoder.encodeExpr(intLit(-5))
        assertEquals("(- 5)", encoded)
    }

    @Test
    fun `verify stronger implies weaker`() = runTest {
        // fnA ensures x > 5, fnB requires x > 0
        // x > 5 implies x > 0, so chaining should be valid
        val fnA = makeFn(
            name = "producer",
            params = listOf(Param("x", intType())),
            ensures = listOf(compare("#sigil:gt", ref("result"), intLit(5)))
        )
        val fnB = makeFn(
            name = "consumer",
            params = listOf(Param("x", intType())),
            requires = listOf(compare("#sigil:gt", ref("result"), intLit(0)))
        )

        val result = solver.verifyChaining(fnA, fnB)
        assertEquals(SmtResult.UNSAT, result.result)
    }
}
