package sigil.effects

import sigil.ast.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EffectCheckerTest {

    private val httpEffectHash = "#effect:http"
    private val dbEffectHash = "#effect:db"

    private val intType = TypeRef(PrimitiveTypes.INT)
    private val stringType = TypeRef(PrimitiveTypes.STRING)
    private val unitType = TypeRef(PrimitiveTypes.UNIT)

    private val httpEffect = EffectDef(
        name = "Http",
        operations = listOf(
            EffectOperation("get", listOf(Param("url", stringType)), stringType)
        ),
        hash = httpEffectHash
    )

    private val dbEffect = EffectDef(
        name = "Db",
        operations = listOf(
            EffectOperation("query", listOf(Param("sql", stringType)), stringType)
        ),
        hash = dbEffectHash
    )

    private fun checker(): EffectChecker {
        val c = EffectChecker()
        c.registerEffect(httpEffect)
        c.registerEffect(dbEffect)
        return c
    }

    @Test
    fun `pure function with no effect usage passes`() {
        val c = checker()
        val fn = FnDef(
            name = "add",
            params = listOf(Param("a", intType), Param("b", intType)),
            returnType = intType,
            effects = emptySet(),
            body = ExprNode.Literal(LiteralValue.IntLit(42))
        )
        val effects = c.checkFnDef(fn)
        assertEquals(emptySet(), effects)
    }

    @Test
    fun `function using an effect must declare it`() {
        val c = checker()
        val fetchHash = "#fn:fetch"
        c.registerFn(fetchHash, setOf(httpEffectHash))

        val fn = FnDef(
            name = "doFetch",
            params = emptyList(),
            returnType = stringType,
            effects = setOf(httpEffectHash),
            body = ExprNode.Apply(
                fn = ExprNode.Ref(fetchHash),
                args = listOf(ExprNode.Literal(LiteralValue.StringLit("https://example.com")))
            )
        )
        val effects = c.checkFnDef(fn)
        assertEquals(setOf(httpEffectHash), effects)
    }

    @Test
    fun `undeclared effect usage throws EffectError`() {
        val c = checker()
        val fetchHash = "#fn:fetch"
        c.registerFn(fetchHash, setOf(httpEffectHash))

        val fn = FnDef(
            name = "sneakyFetch",
            params = emptyList(),
            returnType = stringType,
            effects = emptySet(),
            body = ExprNode.Apply(
                fn = ExprNode.Ref(fetchHash),
                args = listOf(ExprNode.Literal(LiteralValue.StringLit("https://example.com")))
            )
        )
        val error = assertFailsWith<EffectError> { c.checkFnDef(fn) }
        assert(error.message!!.contains("sneakyFetch"))
        assert(error.message!!.contains(httpEffectHash))
    }

    @Test
    fun `effects propagate through function calls`() {
        val c = checker()
        val fetchHash = "#fn:fetch"
        c.registerFn(fetchHash, setOf(httpEffectHash))

        val body = ExprNode.Let(
            binding = "result",
            value = ExprNode.Apply(
                fn = ExprNode.Ref(fetchHash),
                args = listOf(ExprNode.Literal(LiteralValue.StringLit("url")))
            ),
            body = ExprNode.Ref("#local:result")
        )

        val effects = c.collectEffects(body)
        assertEquals(setOf(httpEffectHash), effects)
    }

    @Test
    fun `handler removes effect from propagation`() {
        val c = checker()
        val fetchHash = "#fn:fetch"
        c.registerFn(fetchHash, setOf(httpEffectHash))

        val fnHash = "#fn:cachedFetch"
        val handler = EffectHandler(
            name = "httpCache",
            handles = httpEffectHash,
            implementations = mapOf(
                "get" to ExprNode.Literal(LiteralValue.StringLit("cached"))
            )
        )
        c.registerHandler(fnHash, handler)

        val fn = FnDef(
            name = "cachedFetch",
            params = emptyList(),
            returnType = stringType,
            effects = emptySet(),
            body = ExprNode.Apply(
                fn = ExprNode.Ref(fetchHash),
                args = listOf(ExprNode.Literal(LiteralValue.StringLit("url")))
            ),
            hash = fnHash
        )

        val effects = c.checkFnDef(fn)
        assertEquals(emptySet(), effects)
    }

    @Test
    fun `multiple effects accumulate correctly`() {
        val c = checker()
        val fetchHash = "#fn:fetch"
        val queryHash = "#fn:query"
        c.registerFn(fetchHash, setOf(httpEffectHash))
        c.registerFn(queryHash, setOf(dbEffectHash))

        val fn = FnDef(
            name = "fetchAndStore",
            params = emptyList(),
            returnType = unitType,
            effects = setOf(httpEffectHash, dbEffectHash),
            body = ExprNode.Block(listOf(
                ExprNode.Apply(
                    fn = ExprNode.Ref(fetchHash),
                    args = listOf(ExprNode.Literal(LiteralValue.StringLit("url")))
                ),
                ExprNode.Apply(
                    fn = ExprNode.Ref(queryHash),
                    args = listOf(ExprNode.Literal(LiteralValue.StringLit("INSERT ...")))
                )
            ))
        )
        val effects = c.checkFnDef(fn)
        assertEquals(setOf(httpEffectHash, dbEffectHash), effects)
    }
}
