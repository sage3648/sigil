package sigil.hash

import sigil.ast.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class HasherTest {

    private val intType = TypeRef(PrimitiveTypes.INT)
    private val boolType = TypeRef(PrimitiveTypes.BOOL)

    private fun simpleFnDef(name: String, body: ExprNode) = FnDef(
        name = name,
        params = listOf(Param("x", intType)),
        returnType = intType,
        body = body
    )

    @Test
    fun `same FnDef structure produces same hash regardless of name`() {
        val body = ExprNode.Ref("#sigil:int")
        val fn1 = simpleFnDef("foo", body)
        val fn2 = simpleFnDef("bar", body)
        assertEquals(Hasher.hashFnDef(fn1), Hasher.hashFnDef(fn2))
    }

    @Test
    fun `different body produces different hash`() {
        val fn1 = simpleFnDef("f", ExprNode.Literal(LiteralValue.IntLit(1)))
        val fn2 = simpleFnDef("f", ExprNode.Literal(LiteralValue.IntLit(2)))
        assertNotEquals(Hasher.hashFnDef(fn1), Hasher.hashFnDef(fn2))
    }

    @Test
    fun `same TypeDef with different names produces same hash`() {
        val td1 = TypeDef(
            name = "Color",
            typeParams = emptyList(),
            variants = listOf(Variant("Red", emptyList()), Variant("Blue", emptyList()))
        )
        val td2 = td1.copy(name = "Colour")
        assertEquals(Hasher.hashTypeDef(td1), Hasher.hashTypeDef(td2))
    }

    @Test
    fun `ExprNode hashing is consistent`() {
        val expr = ExprNode.Let(
            binding = "x",
            value = ExprNode.Literal(LiteralValue.IntLit(42)),
            body = ExprNode.Ref("#sigil:int")
        )
        val hash1 = Hasher.hashExpr(expr)
        val hash2 = Hasher.hashExpr(expr)
        assertEquals(hash1, hash2)
        assertEquals(64, hash1.length) // 32 bytes hex = 64 chars
    }

    @Test
    fun `contract changes affect FnDef hash`() {
        val body = ExprNode.Literal(LiteralValue.IntLit(1))
        val fnNoContract = simpleFnDef("f", body)
        val fnWithContract = fnNoContract.copy(
            contract = ContractNode(
                requires = listOf(ContractClause(ExprNode.Literal(LiteralValue.BoolLit(true)))),
                ensures = emptyList()
            )
        )
        assertNotEquals(Hasher.hashFnDef(fnNoContract), Hasher.hashFnDef(fnWithContract))
    }

    @Test
    fun `effect changes affect FnDef hash`() {
        val body = ExprNode.Literal(LiteralValue.IntLit(1))
        val fnNoEffects = simpleFnDef("f", body)
        val fnWithEffects = fnNoEffects.copy(effects = setOf("#sigil:io"))
        assertNotEquals(Hasher.hashFnDef(fnNoEffects), Hasher.hashFnDef(fnWithEffects))
    }
}
