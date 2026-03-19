package sigil.contracts

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import sigil.ast.*
import sigil.types.TypeChecker
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContractVerifierTest {

    private lateinit var tc: TypeChecker
    private lateinit var verifier: ContractVerifier

    @BeforeEach
    fun setUp() {
        tc = TypeChecker()
        tc.registerPrimitives()
        verifier = ContractVerifier(tc)
    }

    private val boolType = TypeRef(PrimitiveTypes.BOOL)
    private val intType = TypeRef(PrimitiveTypes.INT)
    private val stringType = TypeRef(PrimitiveTypes.STRING)

    // Helper: a function ref that takes Int and returns Bool (for predicates)
    private val gtZeroHash = "#gt_zero"
    private val isSortedHash = "#is_sorted"

    private fun registerPredicateFn(hash: String, paramType: TypeRef = intType) {
        tc.registerFnDef(FnDef(
            name = hash.removePrefix("#"),
            params = listOf(Param("x", paramType)),
            returnType = boolType,
            body = ExprNode.Literal(LiteralValue.BoolLit(true)),
            hash = hash
        ))
    }

    // --- 1. Valid requires clause passes verification ---

    @Test
    fun `valid requires clause passes verification`() {
        registerPredicateFn(gtZeroHash)

        val fn = FnDef(
            name = "my_fn",
            params = listOf(Param("n", intType)),
            returnType = intType,
            contract = ContractNode(
                requires = listOf(
                    ContractClause(
                        predicate = ExprNode.Apply(
                            fn = ExprNode.Ref(gtZeroHash),
                            args = listOf(ExprNode.Ref("n"))
                        ),
                        severity = Severity.ABORT
                    )
                ),
                ensures = emptyList()
            ),
            body = ExprNode.Ref("n")
        )

        val errors = verifier.verifyContracts(fn)
        assertTrue(errors.isEmpty(), "Expected no errors but got: $errors")
    }

    // --- 2. Valid ensures clause passes verification ---

    @Test
    fun `valid ensures clause passes verification`() {
        registerPredicateFn(gtZeroHash)

        val fn = FnDef(
            name = "abs",
            params = listOf(Param("n", intType)),
            returnType = intType,
            contract = ContractNode(
                requires = emptyList(),
                ensures = listOf(
                    ContractClause(
                        predicate = ExprNode.Apply(
                            fn = ExprNode.Ref(gtZeroHash),
                            args = listOf(ExprNode.Ref("result"))
                        ),
                        severity = Severity.ABORT
                    )
                )
            ),
            body = ExprNode.Ref("n")
        )

        val errors = verifier.verifyContracts(fn)
        assertTrue(errors.isEmpty(), "Expected no errors but got: $errors")
    }

    // --- 3. Predicate with wrong type (non-Bool) fails ---

    @Test
    fun `predicate with non-Bool return type fails verification`() {
        // Register a function that returns Int instead of Bool
        val intReturningHash = "#int_fn"
        tc.registerFnDef(FnDef(
            name = "int_fn",
            params = listOf(Param("x", intType)),
            returnType = intType,
            body = ExprNode.Ref("x"),
            hash = intReturningHash
        ))

        val fn = FnDef(
            name = "bad_contract",
            params = listOf(Param("n", intType)),
            returnType = intType,
            contract = ContractNode(
                requires = listOf(
                    ContractClause(
                        predicate = ExprNode.Apply(
                            fn = ExprNode.Ref(intReturningHash),
                            args = listOf(ExprNode.Ref("n"))
                        )
                    )
                ),
                ensures = emptyList()
            ),
            body = ExprNode.Ref("n")
        )

        val errors = verifier.verifyContracts(fn)
        assertTrue(errors.isNotEmpty(), "Expected errors for non-Bool predicate")
        assertTrue(errors.any { it.message?.contains("Bool") == true })
    }

    // --- 4. Contract chaining: matching predicates ---

    @Test
    fun `contract chaining with matching predicates is safe`() {
        val isSortedPredicate = ExprNode.Apply(
            fn = ExprNode.Ref(isSortedHash),
            args = listOf(ExprNode.Ref("result"))
        )

        val sortFn = FnDef(
            name = "sort",
            params = listOf(Param("input", intType)),
            returnType = intType,
            contract = ContractNode(
                requires = emptyList(),
                ensures = listOf(ContractClause(predicate = isSortedPredicate))
            ),
            body = ExprNode.Ref("input")
        )

        val bsearchRequiresPredicate = ExprNode.Apply(
            fn = ExprNode.Ref(isSortedHash),
            args = listOf(ExprNode.Ref("result"))
        )

        val bsearchFn = FnDef(
            name = "binary_search",
            params = listOf(Param("data", intType)),
            returnType = intType,
            contract = ContractNode(
                requires = listOf(ContractClause(predicate = bsearchRequiresPredicate)),
                ensures = emptyList()
            ),
            body = ExprNode.Ref("data")
        )

        val result = verifier.checkContractChaining(sortFn, bsearchFn)
        assertTrue(result.safe, "Expected chaining to be safe")
        assertEquals(1, result.satisfied.size)
        assertTrue(result.unsatisfied.isEmpty())
    }

    // --- 5. Contract chaining: mismatched predicates ---

    @Test
    fun `contract chaining with mismatched predicates reports unsatisfied`() {
        val ensuresPredicate = ExprNode.Apply(
            fn = ExprNode.Ref("#positive"),
            args = listOf(ExprNode.Ref("result"))
        )

        val fnA = FnDef(
            name = "fn_a",
            params = listOf(Param("x", intType)),
            returnType = intType,
            contract = ContractNode(
                requires = emptyList(),
                ensures = listOf(ContractClause(predicate = ensuresPredicate))
            ),
            body = ExprNode.Ref("x")
        )

        val requiresPredicate = ExprNode.Apply(
            fn = ExprNode.Ref(isSortedHash),
            args = listOf(ExprNode.Ref("data"))
        )

        val fnB = FnDef(
            name = "fn_b",
            params = listOf(Param("data", intType)),
            returnType = intType,
            contract = ContractNode(
                requires = listOf(ContractClause(predicate = requiresPredicate)),
                ensures = emptyList()
            ),
            body = ExprNode.Ref("data")
        )

        val result = verifier.checkContractChaining(fnA, fnB)
        assertFalse(result.safe, "Expected chaining to be unsafe")
        assertEquals(1, result.unsatisfied.size)
        assertTrue(result.satisfied.isEmpty())
    }

    // --- 6. Generate requires check produces valid assertion ExprNode ---

    @Test
    fun `generateRequiresCheck produces If expression`() {
        val predicate = ExprNode.Apply(
            fn = ExprNode.Ref(gtZeroHash),
            args = listOf(ExprNode.Ref("n"))
        )
        val clause = ContractClause(predicate = predicate, severity = Severity.ABORT)
        val params = listOf(Param("n", intType))

        val check = verifier.generateRequiresCheck(clause, params)

        assertTrue(check is ExprNode.If, "Expected If expression for requires check")
        val ifExpr = check as ExprNode.If
        assertEquals(predicate, ifExpr.cond)
        assertTrue(ifExpr.then_ is ExprNode.Literal)
        assertTrue(ifExpr.else_ is ExprNode.Literal)
    }

    // --- Property tester tests ---

    @Test
    fun `property tester generates values for int type`() {
        val tester = PropertyTester(seed = 42)
        val values = tester.generateValue(intType, 10)
        assertEquals(10, values.size)
        assertTrue(values.all { it is LiteralValue.IntLit })
    }

    @Test
    fun `property tester generates values for bool type`() {
        val tester = PropertyTester(seed = 42)
        val values = tester.generateValue(boolType, 10)
        assertEquals(10, values.size)
        assertTrue(values.all { it is LiteralValue.BoolLit })
    }

    @Test
    fun `property tester returns all passed for function without contracts`() {
        val fn = FnDef(
            name = "no_contract",
            params = listOf(Param("x", intType)),
            returnType = intType,
            body = ExprNode.Ref("x")
        )
        val tester = PropertyTester(seed = 42)
        val result = tester.testProperties(fn, iterations = 50)
        assertEquals(50, result.passed)
        assertEquals(0, result.failed)
    }
}
