package sigil.parser

import sigil.ast.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ParserTest {

    @Test
    fun `parse simple function`() {
        val result = parse("fn add(a: Int, b: Int) -> Int { a + b }")
        assertEquals(1, result.size)
        val fn = assertIs<FnDef>(result[0])
        assertEquals("add", fn.name)
        assertEquals(2, fn.params.size)
        assertEquals("a", fn.params[0].name)
        assertEquals(TypeRef(PrimitiveTypes.INT), fn.params[0].type)
        assertEquals("b", fn.params[1].name)
        assertEquals(TypeRef(PrimitiveTypes.INT), fn.returnType)
        // body should be Apply(Ref("#sigil:add"), [Ref("a"), Ref("b")])
        val body = assertIs<ExprNode.Apply>(fn.body)
        val fnRef = assertIs<ExprNode.Ref>(body.fn)
        assertEquals("#sigil:add", fnRef.hash)
        assertEquals(2, body.args.size)
    }

    @Test
    fun `parse type definition with variants`() {
        val src = """
            type Shape {
                | Circle(radius: F64)
                | Rectangle(width: F64, height: F64)
            }
        """.trimIndent()
        val result = parse(src)
        assertEquals(1, result.size)
        val typeDef = assertIs<TypeDef>(result[0])
        assertEquals("Shape", typeDef.name)
        assertEquals(2, typeDef.variants.size)
        assertEquals("Circle", typeDef.variants[0].name)
        assertEquals(1, typeDef.variants[0].fields.size)
        assertEquals("radius", typeDef.variants[0].fields[0].name)
        assertEquals("Rectangle", typeDef.variants[1].name)
        assertEquals(2, typeDef.variants[1].fields.size)
    }

    @Test
    fun `parse let binding`() {
        val src = "fn main() -> Int { let x = 42\nx }"
        val result = parse(src)
        val fn = assertIs<FnDef>(result[0])
        val letExpr = assertIs<ExprNode.Let>(fn.body)
        assertEquals("x", letExpr.binding)
        val litVal = assertIs<ExprNode.Literal>(letExpr.value)
        val intLit = assertIs<LiteralValue.IntLit>(litVal.value)
        assertEquals(42L, intLit.value)
        val bodyRef = assertIs<ExprNode.Ref>(letExpr.body)
        assertEquals("x", bodyRef.hash)
    }

    @Test
    fun `parse match expression`() {
        val src = """
            fn test(x: Int) -> Int {
                match x {
                    0 => 1,
                    _ => x
                }
            }
        """.trimIndent()
        val result = parse(src)
        val fn = assertIs<FnDef>(result[0])
        val matchExpr = assertIs<ExprNode.Match>(fn.body)
        assertEquals(2, matchExpr.arms.size)
        assertIs<Pattern.LiteralPattern>(matchExpr.arms[0].pattern)
        assertIs<Pattern.WildcardPattern>(matchExpr.arms[1].pattern)
    }

    @Test
    fun `parse lambda`() {
        val src = "fn apply() -> Int { |x: Int| -> x + 1 }"
        val result = parse(src)
        val fn = assertIs<FnDef>(result[0])
        val lambda = assertIs<ExprNode.Lambda>(fn.body)
        assertEquals(1, lambda.params.size)
        assertEquals("x", lambda.params[0].name)
        val body = assertIs<ExprNode.Apply>(lambda.body)
        val addRef = assertIs<ExprNode.Ref>(body.fn)
        assertEquals("#sigil:add", addRef.hash)
    }

    @Test
    fun `parse if then else`() {
        val src = "fn max(a: Int, b: Int) -> Int { if a > b then a else b }"
        val result = parse(src)
        val fn = assertIs<FnDef>(result[0])
        val ifExpr = assertIs<ExprNode.If>(fn.body)
        val cond = assertIs<ExprNode.Apply>(ifExpr.cond)
        val gtRef = assertIs<ExprNode.Ref>(cond.fn)
        assertEquals("#sigil:gt", gtRef.hash)
    }

    @Test
    fun `parse function with contracts`() {
        val src = """
            fn divide(a: Int, b: Int) -> Int {
                requires b != 0
                ensures result >= 0
                a / b
            }
        """.trimIndent()
        val result = parse(src)
        val fn = assertIs<FnDef>(result[0])
        assertTrue(fn.contract != null)
        assertEquals(1, fn.contract!!.requires.size)
        assertEquals(1, fn.contract!!.ensures.size)
        // body should be a / b
        val body = assertIs<ExprNode.Apply>(fn.body)
        val divRef = assertIs<ExprNode.Ref>(body.fn)
        assertEquals("#sigil:div", divRef.hash)
    }

    @Test
    fun `parse function with effects`() {
        val src = """
            fn readFile(path: String) -> String ! IO {
                path
            }
        """.trimIndent()
        val result = parse(src)
        val fn = assertIs<FnDef>(result[0])
        assertTrue(fn.effects.contains("IO"))
        assertEquals(TypeRef(PrimitiveTypes.STRING), fn.returnType)
    }

    @Test
    fun `parse type with type parameters`() {
        val src = """
            type Either<L, R> {
                | Left(value: L)
                | Right(value: R)
            }
        """.trimIndent()
        val result = parse(src)
        val typeDef = assertIs<TypeDef>(result[0])
        assertEquals("Either", typeDef.name)
        assertEquals(2, typeDef.typeParams.size)
        assertEquals("L", typeDef.typeParams[0].name)
        assertEquals("R", typeDef.typeParams[1].name)
    }

    @Test
    fun `parse constructor pattern matching`() {
        val src = """
            fn describe(s: Shape) -> String {
                match s {
                    Circle(r) => "circle",
                    Rectangle(w, h) => "rect"
                }
            }
        """.trimIndent()
        val result = parse(src)
        val fn = assertIs<FnDef>(result[0])
        val matchExpr = assertIs<ExprNode.Match>(fn.body)
        assertEquals(2, matchExpr.arms.size)
        val p1 = assertIs<Pattern.ConstructorPattern>(matchExpr.arms[0].pattern)
        assertEquals("Circle", p1.constructor)
        assertEquals(1, p1.fields.size)
        val p2 = assertIs<Pattern.ConstructorPattern>(matchExpr.arms[1].pattern)
        assertEquals("Rectangle", p2.constructor)
        assertEquals(2, p2.fields.size)
    }

    @Test
    fun `parse list literal`() {
        val src = "fn nums() -> List<Int> { [1, 2, 3] }"
        val result = parse(src)
        val fn = assertIs<FnDef>(result[0])
        val apply = assertIs<ExprNode.Apply>(fn.body)
        val listRef = assertIs<ExprNode.Ref>(apply.fn)
        assertEquals("#sigil:list", listRef.hash)
        assertEquals(3, apply.args.size)
    }

    @Test
    fun `parse unit literal`() {
        val src = "fn noop() -> Unit { () }"
        val result = parse(src)
        val fn = assertIs<FnDef>(result[0])
        val lit = assertIs<ExprNode.Literal>(fn.body)
        assertIs<LiteralValue.UnitLit>(lit.value)
    }

    @Test
    fun `parse binary operator precedence`() {
        // a + b * c should parse as a + (b * c)
        val src = "fn f() -> Int { a + b * c }"
        val result = parse(src)
        val fn = assertIs<FnDef>(result[0])
        val add = assertIs<ExprNode.Apply>(fn.body)
        assertEquals("#sigil:add", (add.fn as ExprNode.Ref).hash)
        // right arg should be mul
        val mul = assertIs<ExprNode.Apply>(add.args[1])
        assertEquals("#sigil:mul", (mul.fn as ExprNode.Ref).hash)
    }

    @Test
    fun `parse string literal`() {
        val src = """fn greet() -> String { "hello world" }"""
        val result = parse(src)
        val fn = assertIs<FnDef>(result[0])
        val lit = assertIs<ExprNode.Literal>(fn.body)
        val str = assertIs<LiteralValue.StringLit>(lit.value)
        assertEquals("hello world", str.value)
    }

    @Test
    fun `parse float literal`() {
        val src = "fn pi() -> F64 { 3.14 }"
        val result = parse(src)
        val fn = assertIs<FnDef>(result[0])
        val lit = assertIs<ExprNode.Literal>(fn.body)
        val f = assertIs<LiteralValue.FloatLit>(lit.value)
        assertEquals(3.14, f.value)
    }

    @Test
    fun `parse boolean literals`() {
        val src = "fn yes() -> Bool { true }"
        val result = parse(src)
        val fn = assertIs<FnDef>(result[0])
        val lit = assertIs<ExprNode.Literal>(fn.body)
        val b = assertIs<LiteralValue.BoolLit>(lit.value)
        assertEquals(true, b.value)
    }

    @Test
    fun `parse nested function calls`() {
        val src = "fn f() -> Int { add(mul(2, 3), 4) }"
        val result = parse(src)
        val fn = assertIs<FnDef>(result[0])
        val outer = assertIs<ExprNode.Apply>(fn.body)
        assertEquals("add", (outer.fn as ExprNode.Ref).hash)
        assertEquals(2, outer.args.size)
        val inner = assertIs<ExprNode.Apply>(outer.args[0])
        assertEquals("mul", (inner.fn as ExprNode.Ref).hash)
    }

    // Section 17.1: Sort with contracts
    @Test
    fun `parse sort with contracts - section 17_1`() {
        val src = """
            fn sort(input: List<Int>) -> List<Int> {
                requires length(input) >= 0
                ensures length(result) == length(input)
                match input {
                    [] => [],
                    _ => input
                }
            }
        """.trimIndent()
        // Note: [] parses as list literal which becomes Apply(Ref("#sigil:list"), [])
        val result = parse(src)
        assertEquals(1, result.size)
        val fn = assertIs<FnDef>(result[0])
        assertEquals("sort", fn.name)
        assertTrue(fn.contract != null)
        assertEquals(1, fn.contract!!.requires.size)
        assertEquals(1, fn.contract!!.ensures.size)
        val body = assertIs<ExprNode.Match>(fn.body)
        assertEquals(2, body.arms.size)
    }

    // Section 17.4: State machine (sum types + pattern matching)
    @Test
    fun `parse state machine - section 17_4`() {
        val src = """
            type State {
                | Idle
                | Running(progress: Int)
                | Finished(result: String)
                | Error(message: String)
            }

            fn transition(state: State, event: String) -> State {
                match state {
                    Idle => Running(0),
                    Running(p) => if p >= 100 then Finished("done") else Running(p + 1),
                    Finished(r) => Finished(r),
                    Error(m) => Error(m)
                }
            }
        """.trimIndent()
        val result = parse(src)
        assertEquals(2, result.size)
        val typeDef = assertIs<TypeDef>(result[0])
        assertEquals("State", typeDef.name)
        assertEquals(4, typeDef.variants.size)
        assertEquals("Idle", typeDef.variants[0].name)
        assertEquals(0, typeDef.variants[0].fields.size)
        assertEquals("Running", typeDef.variants[1].name)
        assertEquals(1, typeDef.variants[1].fields.size)

        val fn = assertIs<FnDef>(result[1])
        assertEquals("transition", fn.name)
        val matchExpr = assertIs<ExprNode.Match>(fn.body)
        assertEquals(4, matchExpr.arms.size)

        // Idle arm => Running(0) should be Apply(Ref("Running"), [Literal(0)])
        val idleBody = assertIs<ExprNode.Apply>(matchExpr.arms[0].body)
        assertEquals("Running", (idleBody.fn as ExprNode.Ref).hash)

        // Running(p) pattern
        val runningPat = assertIs<Pattern.ConstructorPattern>(matchExpr.arms[1].pattern)
        assertEquals("Running", runningPat.constructor)
        assertEquals(1, runningPat.fields.size)

        // Running arm body is an if/then/else
        val runningBody = assertIs<ExprNode.If>(matchExpr.arms[1].body)
    }

    @Test
    fun `parse effect definition`() {
        val src = """
            effect Console {
                fn print(msg: String) -> Unit
                fn readLine() -> String
            }
        """.trimIndent()
        val result = parse(src)
        assertEquals(1, result.size)
        val effectDef = assertIs<EffectDef>(result[0])
        assertEquals("Console", effectDef.name)
        assertEquals(2, effectDef.operations.size)
        assertEquals("print", effectDef.operations[0].name)
        assertEquals("readLine", effectDef.operations[1].name)
    }

    @Test
    fun `parse multiple top-level definitions`() {
        val src = """
            fn add(a: Int, b: Int) -> Int { a + b }
            fn sub(a: Int, b: Int) -> Int { a - b }
        """.trimIndent()
        val result = parse(src)
        assertEquals(2, result.size)
        assertIs<FnDef>(result[0])
        assertIs<FnDef>(result[1])
        assertEquals("add", (result[0] as FnDef).name)
        assertEquals("sub", (result[1] as FnDef).name)
    }

    @Test
    fun `parse generic type reference`() {
        val src = "fn first(xs: List<Int>) -> Option<Int> { xs }"
        val result = parse(src)
        val fn = assertIs<FnDef>(result[0])
        assertEquals(PrimitiveTypes.LIST, fn.params[0].type.defHash)
        assertEquals(1, fn.params[0].type.args.size)
        assertEquals(PrimitiveTypes.INT, fn.params[0].type.args[0].defHash)
        assertEquals(PrimitiveTypes.OPTION, fn.returnType.defHash)
        assertEquals(PrimitiveTypes.INT, fn.returnType.args[0].defHash)
    }

    @Test
    fun `lexer tokenizes all operators`() {
        val tokens = SigilLexer("+ - * / % == != < > <= >= && || ++ -> => ! |").tokenize()
        val types = tokens.map { it.type }.dropLast(1) // drop EOF
        assertEquals(
            listOf(
                TokenType.PLUS, TokenType.MINUS, TokenType.STAR, TokenType.SLASH, TokenType.PERCENT,
                TokenType.EQ, TokenType.NEQ, TokenType.LT, TokenType.GT, TokenType.LTE, TokenType.GTE,
                TokenType.AND, TokenType.OR, TokenType.PLUS_PLUS,
                TokenType.ARROW, TokenType.FAT_ARROW, TokenType.BANG, TokenType.PIPE
            ),
            types
        )
    }

    @Test
    fun `lexer tokenizes keywords`() {
        val tokens = SigilLexer("fn type trait effect module export handler let match if then else requires ensures property").tokenize()
        val types = tokens.map { it.type }.dropLast(1)
        assertEquals(
            listOf(
                TokenType.FN, TokenType.TYPE, TokenType.TRAIT, TokenType.EFFECT,
                TokenType.MODULE, TokenType.EXPORT, TokenType.HANDLER,
                TokenType.LET, TokenType.MATCH, TokenType.IF, TokenType.THEN,
                TokenType.ELSE, TokenType.REQUIRES, TokenType.ENSURES, TokenType.PROPERTY
            ),
            types
        )
    }
}
