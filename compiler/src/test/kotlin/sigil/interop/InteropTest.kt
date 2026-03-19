package sigil.interop

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import sigil.ast.*
import sigil.codegen.jvm.ContractViolation
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InteropTest {

    // --- SigilModule.compile(source) tests ---

    @Test
    fun `compile and call arithmetic function`() {
        val module = SigilModule.compile("fn add(a: Int, b: Int) -> Int { a + b }")
        val result = module.call<Long>("add", 3L, 4L)
        assertEquals(7L, result)
    }

    @Test
    fun `compile and call conditional function`() {
        val module = SigilModule.compile("fn max(a: Int, b: Int) -> Int { if a > b then a else b }")
        assertEquals(10L, module.call<Long>("max", 10L, 5L))
        assertEquals(7L, module.call<Long>("max", 3L, 7L))
        assertEquals(4L, module.call<Long>("max", 4L, 4L))
    }

    @Test
    fun `contract violation from compiled source`() {
        val source = """
            fn positive(x: Int) -> Int {
                requires x > 0
                x
            }
        """.trimIndent()
        val module = SigilModule.compile(source)

        // Positive input succeeds
        assertEquals(5L, module.call<Long>("positive", 5L))

        // Negative input triggers ContractViolation from compiled bytecode
        val ex = assertThrows<java.lang.reflect.InvocationTargetException> {
            module.call<Long>("positive", -1L)
        }
        assertTrue(ex.cause is ContractViolation)
    }

    @Test
    fun `listFunctions returns correct metadata`() {
        val source = """
            fn add(a: Int, b: Int) -> Int { a + b }
            fn square(x: Int) -> Int { x * x }
        """.trimIndent()
        val module = SigilModule.compile(source)

        val fns = module.listFunctions()
        assertEquals(2, fns.size)

        val names = fns.map { it.name }.toSet()
        assertTrue("add" in names)
        assertTrue("square" in names)

        val addInfo = fns.first { it.name == "add" }
        assertEquals(listOf(PrimitiveTypes.INT, PrimitiveTypes.INT), addInfo.paramTypes)
        assertEquals(PrimitiveTypes.INT, addInfo.returnType)
    }

    @Test
    fun `getFunction lookup by name`() {
        val module = SigilModule.compile("fn double(x: Int) -> Int { x + x }")

        val fn = module.getFunction("double")
        assertNotNull(fn)
        assertEquals("double", fn.name)
        assertEquals(listOf(PrimitiveTypes.INT), fn.paramTypes)
        assertEquals(PrimitiveTypes.INT, fn.returnType)
        assertEquals(false, fn.hasContracts)

        assertNull(module.getFunction("nonexistent"))
    }

    @Test
    fun `getFunction reports contracts`() {
        val source = """
            fn positive(x: Int) -> Int {
                requires x > 0
                x
            }
        """.trimIndent()
        val module = SigilModule.compile(source)

        val fn = module.getFunction("positive")
        assertNotNull(fn)
        assertEquals(true, fn.hasContracts)
    }

    @Test
    fun `call multiple functions from same compilation`() {
        val source = """
            fn add(a: Int, b: Int) -> Int { a + b }
            fn sub(a: Int, b: Int) -> Int { a - b }
            fn mul(a: Int, b: Int) -> Int { a * b }
        """.trimIndent()
        val module = SigilModule.compile(source)

        assertEquals(7L, module.call<Long>("add", 3L, 4L))
        assertEquals(2L, module.call<Long>("sub", 5L, 3L))
        assertEquals(12L, module.call<Long>("mul", 3L, 4L))
    }

    @Test
    fun `call unknown function throws`() {
        val module = SigilModule.compile("fn add(a: Int, b: Int) -> Int { a + b }")
        assertThrows<IllegalArgumentException> {
            module.call<Long>("nonexistent", 1L)
        }
    }

    // --- KotlinTypeMapper tests ---

    @Test
    fun `sigilToKotlin maps primitive types`() {
        assertEquals(Long::class, KotlinTypeMapper.sigilToKotlin(TypeRef(PrimitiveTypes.INT)))
        assertEquals(Boolean::class, KotlinTypeMapper.sigilToKotlin(TypeRef(PrimitiveTypes.BOOL)))
        assertEquals(String::class, KotlinTypeMapper.sigilToKotlin(TypeRef(PrimitiveTypes.STRING)))
        assertEquals(Double::class, KotlinTypeMapper.sigilToKotlin(TypeRef(PrimitiveTypes.FLOAT64)))
        assertEquals(Unit::class, KotlinTypeMapper.sigilToKotlin(TypeRef(PrimitiveTypes.UNIT)))
    }

    @Test
    fun `kotlinToSigil round-trips`() {
        assertEquals(TypeRef(PrimitiveTypes.INT), KotlinTypeMapper.kotlinToSigil(Long::class))
        assertEquals(TypeRef(PrimitiveTypes.BOOL), KotlinTypeMapper.kotlinToSigil(Boolean::class))
        assertEquals(TypeRef(PrimitiveTypes.STRING), KotlinTypeMapper.kotlinToSigil(String::class))
    }

    @Test
    fun `jvmDescriptor matches codegen`() {
        assertEquals("J", KotlinTypeMapper.jvmDescriptor(TypeRef(PrimitiveTypes.INT)))
        assertEquals("Z", KotlinTypeMapper.jvmDescriptor(TypeRef(PrimitiveTypes.BOOL)))
        assertEquals("Ljava/lang/String;", KotlinTypeMapper.jvmDescriptor(TypeRef(PrimitiveTypes.STRING)))
        assertEquals("D", KotlinTypeMapper.jvmDescriptor(TypeRef(PrimitiveTypes.FLOAT64)))
        assertEquals("V", KotlinTypeMapper.jvmDescriptor(TypeRef(PrimitiveTypes.UNIT)))
    }

    // --- ContractBridge tests ---

    @Test
    fun `ContractBridge checkRequires in STRICT mode`() {
        val bridge = ContractBridge(EnforcementMode.STRICT)
        val fn = FnDef(
            name = "positive",
            params = listOf(Param("x", TypeRef(PrimitiveTypes.INT))),
            returnType = TypeRef(PrimitiveTypes.INT),
            contract = ContractNode(
                requires = listOf(
                    ContractClause(
                        predicate = ExprNode.Apply(
                            fn = ExprNode.Ref("#sigil:gt"),
                            args = listOf(ExprNode.Ref("x"), ExprNode.Literal(LiteralValue.IntLit(0)))
                        )
                    )
                ),
                ensures = emptyList()
            ),
            body = ExprNode.Ref("x")
        )

        // Should pass for positive input
        bridge.checkRequires(fn, arrayOf(5L))

        // Should throw for negative input
        assertThrows<ContractViolation> {
            bridge.checkRequires(fn, arrayOf(-1L))
        }
    }

    @Test
    fun `ContractBridge WARN mode does not throw`() {
        val bridge = ContractBridge(EnforcementMode.WARN)
        val fn = FnDef(
            name = "positive",
            params = listOf(Param("x", TypeRef(PrimitiveTypes.INT))),
            returnType = TypeRef(PrimitiveTypes.INT),
            contract = ContractNode(
                requires = listOf(
                    ContractClause(
                        predicate = ExprNode.Apply(
                            fn = ExprNode.Ref("#sigil:gt"),
                            args = listOf(ExprNode.Ref("x"), ExprNode.Literal(LiteralValue.IntLit(0)))
                        )
                    )
                ),
                ensures = emptyList()
            ),
            body = ExprNode.Ref("x")
        )

        // Should not throw even for invalid input
        bridge.checkRequires(fn, arrayOf(-1L))
    }

    @Test
    fun `ContractBridge checkEnsures validates result`() {
        val bridge = ContractBridge(EnforcementMode.STRICT)
        val fn = FnDef(
            name = "alwaysPositive",
            params = emptyList(),
            returnType = TypeRef(PrimitiveTypes.INT),
            contract = ContractNode(
                requires = emptyList(),
                ensures = listOf(
                    ContractClause(
                        predicate = ExprNode.Apply(
                            fn = ExprNode.Ref("#sigil:gt"),
                            args = listOf(ExprNode.Ref("result"), ExprNode.Literal(LiteralValue.IntLit(0)))
                        )
                    )
                )
            ),
            body = ExprNode.Literal(LiteralValue.IntLit(1))
        )

        bridge.checkEnsures(fn, 5L)

        assertThrows<ContractViolation> {
            bridge.checkEnsures(fn, -1L)
        }
    }

    // --- SigilModule.compile(fns: List<FnDef>) tests ---

    @Test
    fun `compile from FnDef list`() {
        val intType = TypeRef(PrimitiveTypes.INT)
        val fn = FnDef(
            name = "triple",
            params = listOf(Param("x", intType)),
            returnType = intType,
            body = ExprNode.Apply(
                fn = ExprNode.Ref("#sigil:mul"),
                args = listOf(ExprNode.Ref("x"), ExprNode.Literal(LiteralValue.IntLit(3)))
            )
        )

        val module = SigilModule.compile(listOf(fn))
        assertEquals(9L, module.call<Long>("triple", 3L))
    }
}
