package sigil.types

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import sigil.ast.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TypeCheckerTest {

    private lateinit var tc: TypeChecker

    @BeforeEach
    fun setUp() {
        tc = TypeChecker()
        tc.registerPrimitives()
    }

    // -- Literal inference --

    @Test
    fun `infer int literal`() {
        val expr = ExprNode.Literal(LiteralValue.IntLit(42))
        val type = tc.inferExpr(expr, emptyMap())
        assertEquals(Type.Concrete(PrimitiveTypes.INT), tc.unifier.resolve(type))
    }

    @Test
    fun `infer bool literal`() {
        val expr = ExprNode.Literal(LiteralValue.BoolLit(true))
        val type = tc.inferExpr(expr, emptyMap())
        assertEquals(Type.Concrete(PrimitiveTypes.BOOL), tc.unifier.resolve(type))
    }

    @Test
    fun `infer string literal`() {
        val expr = ExprNode.Literal(LiteralValue.StringLit("hello"))
        val type = tc.inferExpr(expr, emptyMap())
        assertEquals(Type.Concrete(PrimitiveTypes.STRING), tc.unifier.resolve(type))
    }

    @Test
    fun `infer float literal`() {
        val expr = ExprNode.Literal(LiteralValue.FloatLit(3.14))
        val type = tc.inferExpr(expr, emptyMap())
        assertEquals(Type.Concrete(PrimitiveTypes.FLOAT64), tc.unifier.resolve(type))
    }

    @Test
    fun `infer unit literal`() {
        val expr = ExprNode.Literal(LiteralValue.UnitLit)
        val type = tc.inferExpr(expr, emptyMap())
        assertEquals(Type.Concrete(PrimitiveTypes.UNIT), tc.unifier.resolve(type))
    }

    // -- Let binding inference --

    @Test
    fun `infer let binding`() {
        val expr = ExprNode.Let(
            binding = "x",
            value = ExprNode.Literal(LiteralValue.IntLit(10)),
            body = ExprNode.Ref("x")
        )
        val type = tc.inferExpr(expr, emptyMap())
        assertEquals(Type.Concrete(PrimitiveTypes.INT), tc.unifier.resolve(type))
    }

    @Test
    fun `infer nested let bindings`() {
        val expr = ExprNode.Let(
            binding = "x",
            value = ExprNode.Literal(LiteralValue.IntLit(1)),
            body = ExprNode.Let(
                binding = "y",
                value = ExprNode.Literal(LiteralValue.BoolLit(true)),
                body = ExprNode.Ref("y")
            )
        )
        val type = tc.inferExpr(expr, emptyMap())
        assertEquals(Type.Concrete(PrimitiveTypes.BOOL), tc.unifier.resolve(type))
    }

    // -- Lambda type checking --

    @Test
    fun `infer lambda type`() {
        val expr = ExprNode.Lambda(
            params = listOf(Param("x", TypeRef(PrimitiveTypes.INT))),
            body = ExprNode.Ref("x")
        )
        val type = tc.inferExpr(expr, emptyMap())
        val resolved = tc.unifier.resolve(type)
        assertTrue(resolved is Type.Function)
        assertEquals(listOf(Type.Concrete(PrimitiveTypes.INT)), (resolved as Type.Function).params)
        assertEquals(Type.Concrete(PrimitiveTypes.INT), resolved.returnType)
    }

    @Test
    fun `infer multi-param lambda`() {
        val expr = ExprNode.Lambda(
            params = listOf(
                Param("x", TypeRef(PrimitiveTypes.INT)),
                Param("y", TypeRef(PrimitiveTypes.BOOL))
            ),
            body = ExprNode.Ref("x")
        )
        val type = tc.inferExpr(expr, emptyMap())
        val resolved = tc.unifier.resolve(type) as Type.Function
        assertEquals(2, resolved.params.size)
        assertEquals(Type.Concrete(PrimitiveTypes.INT), resolved.returnType)
    }

    // -- Function application --

    @Test
    fun `infer function application`() {
        val fn = FnDef(
            name = "add1",
            params = listOf(Param("x", TypeRef(PrimitiveTypes.INT))),
            returnType = TypeRef(PrimitiveTypes.INT),
            body = ExprNode.Ref("x"),
            hash = "#test:add1"
        )
        tc.registerFnDef(fn)

        val expr = ExprNode.Apply(
            fn = ExprNode.Ref("#test:add1"),
            args = listOf(ExprNode.Literal(LiteralValue.IntLit(5)))
        )
        val type = tc.inferExpr(expr, emptyMap())
        assertEquals(Type.Concrete(PrimitiveTypes.INT), tc.unifier.resolve(type))
    }

    @Test
    fun `application argument count mismatch`() {
        val fn = FnDef(
            name = "f",
            params = listOf(Param("x", TypeRef(PrimitiveTypes.INT))),
            returnType = TypeRef(PrimitiveTypes.INT),
            body = ExprNode.Ref("x"),
            hash = "#test:f"
        )
        tc.registerFnDef(fn)

        val expr = ExprNode.Apply(
            fn = ExprNode.Ref("#test:f"),
            args = listOf(
                ExprNode.Literal(LiteralValue.IntLit(1)),
                ExprNode.Literal(LiteralValue.IntLit(2))
            )
        )
        assertThrows<TypeCheckError> { tc.inferExpr(expr, emptyMap()) }
    }

    // -- If expression --

    @Test
    fun `infer if expression with matching branches`() {
        val expr = ExprNode.If(
            cond = ExprNode.Literal(LiteralValue.BoolLit(true)),
            then_ = ExprNode.Literal(LiteralValue.IntLit(1)),
            else_ = ExprNode.Literal(LiteralValue.IntLit(2))
        )
        val type = tc.inferExpr(expr, emptyMap())
        assertEquals(Type.Concrete(PrimitiveTypes.INT), tc.unifier.resolve(type))
    }

    @Test
    fun `if expression with non-bool condition fails`() {
        val expr = ExprNode.If(
            cond = ExprNode.Literal(LiteralValue.IntLit(1)),
            then_ = ExprNode.Literal(LiteralValue.IntLit(1)),
            else_ = ExprNode.Literal(LiteralValue.IntLit(2))
        )
        assertThrows<TypeCheckError> { tc.inferExpr(expr, emptyMap()) }
    }

    @Test
    fun `if expression with mismatched branches fails`() {
        val expr = ExprNode.If(
            cond = ExprNode.Literal(LiteralValue.BoolLit(true)),
            then_ = ExprNode.Literal(LiteralValue.IntLit(1)),
            else_ = ExprNode.Literal(LiteralValue.StringLit("hello"))
        )
        assertThrows<TypeCheckError> { tc.inferExpr(expr, emptyMap()) }
    }

    // -- Pattern matching --

    @Test
    fun `infer match expression with literal patterns`() {
        val expr = ExprNode.Match(
            scrutinee = ExprNode.Literal(LiteralValue.IntLit(1)),
            arms = listOf(
                MatchArm(
                    pattern = Pattern.LiteralPattern(LiteralValue.IntLit(1)),
                    body = ExprNode.Literal(LiteralValue.StringLit("one"))
                ),
                MatchArm(
                    pattern = Pattern.WildcardPattern,
                    body = ExprNode.Literal(LiteralValue.StringLit("other"))
                )
            )
        )
        val type = tc.inferExpr(expr, emptyMap())
        assertEquals(Type.Concrete(PrimitiveTypes.STRING), tc.unifier.resolve(type))
    }

    @Test
    fun `match with binding pattern`() {
        val expr = ExprNode.Match(
            scrutinee = ExprNode.Literal(LiteralValue.IntLit(42)),
            arms = listOf(
                MatchArm(
                    pattern = Pattern.BindingPattern("x"),
                    body = ExprNode.Ref("x")
                )
            )
        )
        val type = tc.inferExpr(expr, emptyMap())
        assertEquals(Type.Concrete(PrimitiveTypes.INT), tc.unifier.resolve(type))
    }

    @Test
    fun `match arms with inconsistent types fails`() {
        val expr = ExprNode.Match(
            scrutinee = ExprNode.Literal(LiteralValue.IntLit(1)),
            arms = listOf(
                MatchArm(
                    pattern = Pattern.LiteralPattern(LiteralValue.IntLit(1)),
                    body = ExprNode.Literal(LiteralValue.IntLit(10))
                ),
                MatchArm(
                    pattern = Pattern.WildcardPattern,
                    body = ExprNode.Literal(LiteralValue.StringLit("other"))
                )
            )
        )
        assertThrows<TypeCheckError> { tc.inferExpr(expr, emptyMap()) }
    }

    // -- Type mismatch errors --

    @Test
    fun `undefined reference fails`() {
        val expr = ExprNode.Ref("nonexistent")
        assertThrows<TypeCheckError> { tc.inferExpr(expr, emptyMap()) }
    }

    @Test
    fun `application type mismatch fails`() {
        val fn = FnDef(
            name = "intFn",
            params = listOf(Param("x", TypeRef(PrimitiveTypes.INT))),
            returnType = TypeRef(PrimitiveTypes.INT),
            body = ExprNode.Ref("x"),
            hash = "#test:intFn"
        )
        tc.registerFnDef(fn)

        val expr = ExprNode.Apply(
            fn = ExprNode.Ref("#test:intFn"),
            args = listOf(ExprNode.Literal(LiteralValue.StringLit("wrong")))
        )
        assertThrows<TypeCheckError> { tc.inferExpr(expr, emptyMap()) }
    }

    // -- Generic function instantiation --

    @Test
    fun `generic identity function via lambda`() {
        // Create a lambda that acts as identity: (x: Int) -> x
        val identityInt = ExprNode.Lambda(
            params = listOf(Param("x", TypeRef(PrimitiveTypes.INT))),
            body = ExprNode.Ref("x")
        )
        val type = tc.inferExpr(identityInt, emptyMap())
        val resolved = tc.unifier.resolve(type) as Type.Function
        assertEquals(Type.Concrete(PrimitiveTypes.INT), resolved.params[0])
        assertEquals(Type.Concrete(PrimitiveTypes.INT), resolved.returnType)
    }

    // -- FnDef checking --

    @Test
    fun `checkFnDef with correct types`() {
        val fn = FnDef(
            name = "identity",
            params = listOf(Param("x", TypeRef(PrimitiveTypes.INT))),
            returnType = TypeRef(PrimitiveTypes.INT),
            body = ExprNode.Ref("x"),
            hash = "#test:identity"
        )
        val type = tc.checkFnDef(fn)
        assertTrue(type is Type.Function)
    }

    @Test
    fun `checkFnDef with mismatched return type fails`() {
        val fn = FnDef(
            name = "bad",
            params = listOf(Param("x", TypeRef(PrimitiveTypes.INT))),
            returnType = TypeRef(PrimitiveTypes.STRING),
            body = ExprNode.Ref("x"),
            hash = "#test:bad"
        )
        assertThrows<TypeCheckError> { tc.checkFnDef(fn) }
    }

    // -- Block expression --

    @Test
    fun `infer block returns last expression type`() {
        val expr = ExprNode.Block(
            exprs = listOf(
                ExprNode.Literal(LiteralValue.IntLit(1)),
                ExprNode.Literal(LiteralValue.StringLit("result"))
            )
        )
        val type = tc.inferExpr(expr, emptyMap())
        assertEquals(Type.Concrete(PrimitiveTypes.STRING), tc.unifier.resolve(type))
    }

    @Test
    fun `infer empty block returns Unit`() {
        val expr = ExprNode.Block(exprs = emptyList())
        val type = tc.inferExpr(expr, emptyMap())
        assertEquals(Type.Concrete(PrimitiveTypes.UNIT), tc.unifier.resolve(type))
    }

    // -- Unifier tests --

    @Test
    fun `unify concrete types succeeds`() {
        val u = Unifier()
        u.unify(Type.Concrete(PrimitiveTypes.INT), Type.Concrete(PrimitiveTypes.INT))
    }

    @Test
    fun `unify different concrete types fails`() {
        val u = Unifier()
        assertThrows<UnificationError> {
            u.unify(Type.Concrete(PrimitiveTypes.INT), Type.Concrete(PrimitiveTypes.STRING))
        }
    }

    @Test
    fun `unify variable with concrete`() {
        val u = Unifier()
        val v = u.freshVar()
        u.unify(v, Type.Concrete(PrimitiveTypes.INT))
        assertEquals(Type.Concrete(PrimitiveTypes.INT), u.resolve(v))
    }

    // -- TraitResolver tests --

    @Test
    fun `trait resolver checks registered impl`() {
        val tr = TraitResolver()
        tr.registerImpl(PrimitiveTypes.INT, "#sigil:show")
        assertTrue(tr.checkBound(Type.Concrete(PrimitiveTypes.INT), "#sigil:show"))
    }

    @Test
    fun `trait resolver returns false for unregistered impl`() {
        val tr = TraitResolver()
        assertEquals(false, tr.checkBound(Type.Concrete(PrimitiveTypes.INT), "#sigil:show"))
    }
}
