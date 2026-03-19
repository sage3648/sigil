package sigil.stdlib

import org.junit.jupiter.api.Test
import sigil.ast.*
import sigil.codegen.jvm.JvmCodegen
import sigil.codegen.jvm.JvmLinker
import sigil.interop.SigilModule
import sigil.parser.parse
import sigil.types.TypeChecker
import kotlin.test.assertEquals

class StdlibTest {

    // --- Parser resolution tests ---

    @Test
    fun `parser resolves abs to sigil builtin`() {
        val parsed = parse("fn test(x: Int) -> Int { abs(x) }")
        val fn = parsed.first() as FnDef
        val apply = fn.body as ExprNode.Apply
        val ref = apply.fn as ExprNode.Ref
        assertEquals("#sigil:abs", ref.hash)
    }

    @Test
    fun `parser resolves min to sigil builtin`() {
        val parsed = parse("fn test(a: Int, b: Int) -> Int { min(a, b) }")
        val fn = parsed.first() as FnDef
        val apply = fn.body as ExprNode.Apply
        val ref = apply.fn as ExprNode.Ref
        assertEquals("#sigil:min", ref.hash)
    }

    @Test
    fun `parser resolves max to sigil builtin`() {
        val parsed = parse("fn test(a: Int, b: Int) -> Int { max(a, b) }")
        val fn = parsed.first() as FnDef
        val apply = fn.body as ExprNode.Apply
        val ref = apply.fn as ExprNode.Ref
        assertEquals("#sigil:max", ref.hash)
    }

    @Test
    fun `parser resolves str_length to sigil builtin`() {
        val parsed = parse("fn test(s: String) -> Int { str_length(s) }")
        val fn = parsed.first() as FnDef
        val apply = fn.body as ExprNode.Apply
        val ref = apply.fn as ExprNode.Ref
        assertEquals("#sigil:str_length", ref.hash)
    }

    @Test
    fun `parser resolves str_upper to sigil builtin`() {
        val parsed = parse("fn test(s: String) -> String { str_upper(s) }")
        val fn = parsed.first() as FnDef
        val apply = fn.body as ExprNode.Apply
        val ref = apply.fn as ExprNode.Ref
        assertEquals("#sigil:str_upper", ref.hash)
    }

    @Test
    fun `parser resolves str_lower to sigil builtin`() {
        val parsed = parse("fn test(s: String) -> String { str_lower(s) }")
        val fn = parsed.first() as FnDef
        val apply = fn.body as ExprNode.Apply
        val ref = apply.fn as ExprNode.Ref
        assertEquals("#sigil:str_lower", ref.hash)
    }

    // --- Type checker tests ---

    @Test
    fun `type checker accepts abs with Int arg`() {
        val tc = TypeChecker()
        tc.registerPrimitives()
        val fn = FnDef(
            name = "test",
            params = listOf(Param("x", TypeRef(PrimitiveTypes.INT))),
            returnType = TypeRef(PrimitiveTypes.INT),
            body = ExprNode.Apply(ExprNode.Ref("#sigil:abs"), listOf(ExprNode.Ref("x")))
        )
        tc.checkFnDef(fn)
    }

    @Test
    fun `type checker accepts min with two Int args`() {
        val tc = TypeChecker()
        tc.registerPrimitives()
        val fn = FnDef(
            name = "test",
            params = listOf(Param("a", TypeRef(PrimitiveTypes.INT)), Param("b", TypeRef(PrimitiveTypes.INT))),
            returnType = TypeRef(PrimitiveTypes.INT),
            body = ExprNode.Apply(ExprNode.Ref("#sigil:min"), listOf(ExprNode.Ref("a"), ExprNode.Ref("b")))
        )
        tc.checkFnDef(fn)
    }

    @Test
    fun `type checker accepts str_length with String arg returning Int`() {
        val tc = TypeChecker()
        tc.registerPrimitives()
        val fn = FnDef(
            name = "test",
            params = listOf(Param("s", TypeRef(PrimitiveTypes.STRING))),
            returnType = TypeRef(PrimitiveTypes.INT),
            body = ExprNode.Apply(ExprNode.Ref("#sigil:str_length"), listOf(ExprNode.Ref("s")))
        )
        tc.checkFnDef(fn)
    }

    @Test
    fun `type checker accepts str_upper with String arg returning String`() {
        val tc = TypeChecker()
        tc.registerPrimitives()
        val fn = FnDef(
            name = "test",
            params = listOf(Param("s", TypeRef(PrimitiveTypes.STRING))),
            returnType = TypeRef(PrimitiveTypes.STRING),
            body = ExprNode.Apply(ExprNode.Ref("#sigil:str_upper"), listOf(ExprNode.Ref("s")))
        )
        tc.checkFnDef(fn)
    }

    // --- Codegen + execution tests ---

    @Test
    fun `abs returns absolute value`() {
        val intType = TypeRef(PrimitiveTypes.INT)
        val fn = FnDef(
            name = "testAbs",
            params = listOf(Param("x", intType)),
            returnType = intType,
            body = ExprNode.Apply(ExprNode.Ref("#sigil:abs"), listOf(ExprNode.Ref("x")))
        )
        val codegen = JvmCodegen()
        val hash = "abs_test_hash_01"
        val bytes = codegen.compileFnDef(fn, hash)
        val className = "Sigil_abs_test"

        val loader = JvmLinker().loadClasses(mapOf(className to bytes))
        val clazz = loader.loadClass(className)
        val method = clazz.getMethod("testAbs", Long::class.java)

        assertEquals(5L, method.invoke(null, 5L))
        assertEquals(5L, method.invoke(null, -5L))
        assertEquals(0L, method.invoke(null, 0L))
    }

    @Test
    fun `min returns smaller value`() {
        val intType = TypeRef(PrimitiveTypes.INT)
        val fn = FnDef(
            name = "testMin",
            params = listOf(Param("a", intType), Param("b", intType)),
            returnType = intType,
            body = ExprNode.Apply(ExprNode.Ref("#sigil:min"), listOf(ExprNode.Ref("a"), ExprNode.Ref("b")))
        )
        val codegen = JvmCodegen()
        val hash = "min_test_hash_01"
        val bytes = codegen.compileFnDef(fn, hash)
        val className = "Sigil_min_test"

        val loader = JvmLinker().loadClasses(mapOf(className to bytes))
        val clazz = loader.loadClass(className)
        val method = clazz.getMethod("testMin", Long::class.java, Long::class.java)

        assertEquals(3L, method.invoke(null, 3L, 7L))
        assertEquals(3L, method.invoke(null, 7L, 3L))
        assertEquals(5L, method.invoke(null, 5L, 5L))
    }

    @Test
    fun `max returns larger value`() {
        val intType = TypeRef(PrimitiveTypes.INT)
        val fn = FnDef(
            name = "testMax",
            params = listOf(Param("a", intType), Param("b", intType)),
            returnType = intType,
            body = ExprNode.Apply(ExprNode.Ref("#sigil:max"), listOf(ExprNode.Ref("a"), ExprNode.Ref("b")))
        )
        val codegen = JvmCodegen()
        val hash = "max_test_hash_01"
        val bytes = codegen.compileFnDef(fn, hash)
        val className = "Sigil_max_test"

        val loader = JvmLinker().loadClasses(mapOf(className to bytes))
        val clazz = loader.loadClass(className)
        val method = clazz.getMethod("testMax", Long::class.java, Long::class.java)

        assertEquals(7L, method.invoke(null, 3L, 7L))
        assertEquals(7L, method.invoke(null, 7L, 3L))
        assertEquals(5L, method.invoke(null, 5L, 5L))
    }

    @Test
    fun `str_length returns string length`() {
        val stringType = TypeRef(PrimitiveTypes.STRING)
        val intType = TypeRef(PrimitiveTypes.INT)
        val fn = FnDef(
            name = "testLen",
            params = listOf(Param("s", stringType)),
            returnType = intType,
            body = ExprNode.Apply(ExprNode.Ref("#sigil:str_length"), listOf(ExprNode.Ref("s")))
        )
        val codegen = JvmCodegen()
        val hash = "len_test_hash_01"
        val bytes = codegen.compileFnDef(fn, hash)
        val className = "Sigil_len_test"

        val loader = JvmLinker().loadClasses(mapOf(className to bytes))
        val clazz = loader.loadClass(className)
        val method = clazz.getMethod("testLen", String::class.java)

        assertEquals(5L, method.invoke(null, "hello"))
        assertEquals(0L, method.invoke(null, ""))
        assertEquals(11L, method.invoke(null, "hello world"))
    }

    @Test
    fun `str_upper returns uppercase string`() {
        val stringType = TypeRef(PrimitiveTypes.STRING)
        val fn = FnDef(
            name = "testUpper",
            params = listOf(Param("s", stringType)),
            returnType = stringType,
            body = ExprNode.Apply(ExprNode.Ref("#sigil:str_upper"), listOf(ExprNode.Ref("s")))
        )
        val codegen = JvmCodegen()
        val hash = "upr_test_hash_01"
        val bytes = codegen.compileFnDef(fn, hash)
        val className = "Sigil_upr_test"

        val loader = JvmLinker().loadClasses(mapOf(className to bytes))
        val clazz = loader.loadClass(className)
        val method = clazz.getMethod("testUpper", String::class.java)

        assertEquals("HELLO", method.invoke(null, "hello"))
        assertEquals("HELLO WORLD", method.invoke(null, "Hello World"))
        assertEquals("", method.invoke(null, ""))
    }

    @Test
    fun `str_lower returns lowercase string`() {
        val stringType = TypeRef(PrimitiveTypes.STRING)
        val fn = FnDef(
            name = "testLower",
            params = listOf(Param("s", stringType)),
            returnType = stringType,
            body = ExprNode.Apply(ExprNode.Ref("#sigil:str_lower"), listOf(ExprNode.Ref("s")))
        )
        val codegen = JvmCodegen()
        val hash = "lwr_test_hash_01"
        val bytes = codegen.compileFnDef(fn, hash)
        val className = "Sigil_lwr_test"

        val loader = JvmLinker().loadClasses(mapOf(className to bytes))
        val clazz = loader.loadClass(className)
        val method = clazz.getMethod("testLower", String::class.java)

        assertEquals("hello", method.invoke(null, "HELLO"))
        assertEquals("hello world", method.invoke(null, "Hello World"))
        assertEquals("", method.invoke(null, ""))
    }

    // --- End-to-end source compilation tests ---

    @Test
    fun `abs compiles and runs from source`() {
        val module = SigilModule.compile("fn myabs(x: Int) -> Int { abs(x) }")
        assertEquals(5L, module.call<Long>("myabs", -5L))
        assertEquals(3L, module.call<Long>("myabs", 3L))
    }

    @Test
    fun `min compiles and runs from source`() {
        val module = SigilModule.compile("fn mymin(a: Int, b: Int) -> Int { min(a, b) }")
        assertEquals(2L, module.call<Long>("mymin", 2L, 8L))
        assertEquals(2L, module.call<Long>("mymin", 8L, 2L))
    }

    @Test
    fun `max compiles and runs from source`() {
        val module = SigilModule.compile("fn mymax(a: Int, b: Int) -> Int { max(a, b) }")
        assertEquals(8L, module.call<Long>("mymax", 2L, 8L))
        assertEquals(8L, module.call<Long>("mymax", 8L, 2L))
    }

    @Test
    fun `str_length compiles and runs from source`() {
        val module = SigilModule.compile("fn mylen(s: String) -> Int { str_length(s) }")
        assertEquals(5L, module.call<Long>("mylen", "hello"))
        assertEquals(0L, module.call<Long>("mylen", ""))
    }

    @Test
    fun `str_upper compiles and runs from source`() {
        val module = SigilModule.compile("fn myupper(s: String) -> String { str_upper(s) }")
        assertEquals("HELLO", module.call<Any>("myupper", "hello"))
    }

    @Test
    fun `str_lower compiles and runs from source`() {
        val module = SigilModule.compile("fn mylower(s: String) -> String { str_lower(s) }")
        assertEquals("hello", module.call<Any>("mylower", "HELLO"))
    }
}
