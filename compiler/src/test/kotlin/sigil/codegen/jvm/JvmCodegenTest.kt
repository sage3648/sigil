package sigil.codegen.jvm

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import sigil.ast.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JvmCodegenTest {

    private val intType = TypeRef(PrimitiveTypes.INT)
    private val boolType = TypeRef(PrimitiveTypes.BOOL)

    private fun intLit(n: Long) = ExprNode.Literal(LiteralValue.IntLit(n))
    private fun boolLit(b: Boolean) = ExprNode.Literal(LiteralValue.BoolLit(b))
    private fun ref(h: String) = ExprNode.Ref(h)
    private fun binOp(op: String, a: ExprNode, b: ExprNode) =
        ExprNode.Apply(ExprNode.Ref(op), listOf(a, b))

    @Test
    fun `compile literal function`() {
        // fn answer() -> Int { 42 }
        val fn = FnDef(
            name = "answer",
            params = emptyList(),
            returnType = intType,
            body = intLit(42)
        )
        val codegen = JvmCodegen()
        val hash = "abcd1234abcd1234"
        val bytes = codegen.compileFnDef(fn, hash)
        val className = "Sigil_abcd1234"

        val loader = JvmLinker().loadClasses(mapOf(className to bytes))
        val clazz = loader.loadClass(className)
        val method = clazz.getMethod("answer")
        val result = method.invoke(null)
        assertEquals(42L, result)
    }

    @Test
    fun `compile addition`() {
        // fn add(a: Int, b: Int) -> Int { a + b }
        val fn = FnDef(
            name = "add",
            params = listOf(Param("a", intType), Param("b", intType)),
            returnType = intType,
            body = binOp("#sigil:add", ref("a"), ref("b"))
        )
        val codegen = JvmCodegen()
        val hash = "add12345add12345"
        val bytes = codegen.compileFnDef(fn, hash)
        val className = "Sigil_add12345"

        val loader = JvmLinker().loadClasses(mapOf(className to bytes))
        val clazz = loader.loadClass(className)
        val method = clazz.getMethod("add", Long::class.java, Long::class.java)
        val result = method.invoke(null, 3L, 4L)
        assertEquals(7L, result)
    }

    @Test
    fun `compile if expression`() {
        // fn max(a: Int, b: Int) -> Int { if a > b then a else b }
        val fn = FnDef(
            name = "max",
            params = listOf(Param("a", intType), Param("b", intType)),
            returnType = intType,
            body = ExprNode.If(
                cond = binOp("#sigil:gt", ref("a"), ref("b")),
                then_ = ref("a"),
                else_ = ref("b")
            )
        )
        val codegen = JvmCodegen()
        val hash = "max12345max12345"
        val bytes = codegen.compileFnDef(fn, hash)
        val className = "Sigil_max12345"

        val loader = JvmLinker().loadClasses(mapOf(className to bytes))
        val clazz = loader.loadClass(className)
        val method = clazz.getMethod("max", Long::class.java, Long::class.java)

        assertEquals(5L, method.invoke(null, 5L, 3L))
        assertEquals(7L, method.invoke(null, 2L, 7L))
        assertEquals(4L, method.invoke(null, 4L, 4L)) // equal case: a > b is false, returns b
    }

    @Test
    fun `compile let binding`() {
        // fn double(x: Int) -> Int { let y = x + x; y }
        val fn = FnDef(
            name = "double",
            params = listOf(Param("x", intType)),
            returnType = intType,
            body = ExprNode.Let(
                binding = "y",
                value = binOp("#sigil:add", ref("x"), ref("x")),
                body = ref("y")
            )
        )
        val codegen = JvmCodegen()
        val hash = "dbl12345dbl12345"
        val bytes = codegen.compileFnDef(fn, hash)
        val className = "Sigil_dbl12345"

        val loader = JvmLinker().loadClasses(mapOf(className to bytes))
        val clazz = loader.loadClass(className)
        val method = clazz.getMethod("double", Long::class.java)
        assertEquals(10L, method.invoke(null, 5L))
    }

    @Test
    fun `compile with requires contract - passing`() {
        // fn abs(x: Int) -> Int requires x > 0 { x }
        val fn = FnDef(
            name = "abs",
            params = listOf(Param("x", intType)),
            returnType = intType,
            contract = ContractNode(
                requires = listOf(
                    ContractClause(
                        predicate = binOp("#sigil:gt", ref("x"), intLit(0))
                    )
                ),
                ensures = emptyList()
            ),
            body = ref("x")
        )
        val codegen = JvmCodegen()
        val hash = "abs12345abs12345"
        val bytes = codegen.compileFnDef(fn, hash)
        val className = "Sigil_abs12345"

        val loader = JvmLinker().loadClasses(mapOf(className to bytes))
        val clazz = loader.loadClass(className)
        val method = clazz.getMethod("abs", Long::class.java)

        // Should succeed for positive input
        assertEquals(5L, method.invoke(null, 5L))
    }

    @Test
    fun `compile with requires contract - failing`() {
        // fn abs(x: Int) -> Int requires x > 0 { x }
        val fn = FnDef(
            name = "abs",
            params = listOf(Param("x", intType)),
            returnType = intType,
            contract = ContractNode(
                requires = listOf(
                    ContractClause(
                        predicate = binOp("#sigil:gt", ref("x"), intLit(0))
                    )
                ),
                ensures = emptyList()
            ),
            body = ref("x")
        )
        val codegen = JvmCodegen()
        val hash = "abs22345abs22345"
        val bytes = codegen.compileFnDef(fn, hash)
        val className = "Sigil_abs22345"

        val loader = JvmLinker().loadClasses(mapOf(className to bytes))
        val clazz = loader.loadClass(className)
        val method = clazz.getMethod("abs", Long::class.java)

        // Should throw ContractViolation for 0
        val ex = assertThrows<java.lang.reflect.InvocationTargetException> {
            method.invoke(null, 0L)
        }
        assertTrue(ex.cause is ContractViolation)
    }

    @Test
    fun `compile boolean function`() {
        // fn isPositive(x: Int) -> Bool { x > 0 }
        val fn = FnDef(
            name = "isPositive",
            params = listOf(Param("x", intType)),
            returnType = boolType,
            body = binOp("#sigil:gt", ref("x"), intLit(0))
        )
        val codegen = JvmCodegen()
        val hash = "isp12345isp12345"
        val bytes = codegen.compileFnDef(fn, hash)
        val className = "Sigil_isp12345"

        val loader = JvmLinker().loadClasses(mapOf(className to bytes))
        val clazz = loader.loadClass(className)
        val method = clazz.getMethod("isPositive", Long::class.java)

        assertEquals(true, method.invoke(null, 5L))
        assertEquals(false, method.invoke(null, 0L))
        assertEquals(false, method.invoke(null, -3L))
    }

    @Test
    fun `compile subtraction and multiplication`() {
        // fn calc(a: Int, b: Int) -> Int { (a - b) * b }
        val fn = FnDef(
            name = "calc",
            params = listOf(Param("a", intType), Param("b", intType)),
            returnType = intType,
            body = binOp(
                "#sigil:mul",
                binOp("#sigil:sub", ref("a"), ref("b")),
                ref("b")
            )
        )
        val codegen = JvmCodegen()
        val hash = "calc1234calc1234"
        val bytes = codegen.compileFnDef(fn, hash)
        val className = "Sigil_calc1234"

        val loader = JvmLinker().loadClasses(mapOf(className to bytes))
        val clazz = loader.loadClass(className)
        val method = clazz.getMethod("calc", Long::class.java, Long::class.java)

        // (10 - 3) * 3 = 21
        assertEquals(21L, method.invoke(null, 10L, 3L))
    }

    @Test
    fun `type descriptor mapping`() {
        val codegen = JvmCodegen()
        assertEquals("J", codegen.typeDescriptor(TypeRef(PrimitiveTypes.INT)))
        assertEquals("J", codegen.typeDescriptor(TypeRef(PrimitiveTypes.INT64)))
        assertEquals("I", codegen.typeDescriptor(TypeRef(PrimitiveTypes.INT32)))
        assertEquals("D", codegen.typeDescriptor(TypeRef(PrimitiveTypes.FLOAT64)))
        assertEquals("Z", codegen.typeDescriptor(TypeRef(PrimitiveTypes.BOOL)))
        assertEquals("Ljava/lang/String;", codegen.typeDescriptor(TypeRef(PrimitiveTypes.STRING)))
        assertEquals("V", codegen.typeDescriptor(TypeRef(PrimitiveTypes.UNIT)))
    }
}
