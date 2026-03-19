package sigil.parser

import sigil.ast.*

class ParseError(message: String, val line: Int, val col: Int) : Exception("$message at $line:$col")

class SigilParser(private val tokens: List<Token>) {
    private var pos = 0

    private val primitiveTypeMap = mapOf(
        "Int" to PrimitiveTypes.INT,
        "I32" to PrimitiveTypes.INT32,
        "I64" to PrimitiveTypes.INT64,
        "F64" to PrimitiveTypes.FLOAT64,
        "Bool" to PrimitiveTypes.BOOL,
        "String" to PrimitiveTypes.STRING,
        "Unit" to PrimitiveTypes.UNIT,
        "Bytes" to PrimitiveTypes.BYTES,
        "List" to PrimitiveTypes.LIST,
        "Map" to PrimitiveTypes.MAP,
        "Option" to PrimitiveTypes.OPTION,
        "Result" to PrimitiveTypes.RESULT,
    )

    private val binaryOpMap = mapOf(
        TokenType.PLUS to "#sigil:add",
        TokenType.MINUS to "#sigil:sub",
        TokenType.STAR to "#sigil:mul",
        TokenType.SLASH to "#sigil:div",
        TokenType.PERCENT to "#sigil:mod",
        TokenType.EQ to "#sigil:eq",
        TokenType.NEQ to "#sigil:neq",
        TokenType.LT to "#sigil:lt",
        TokenType.GT to "#sigil:gt",
        TokenType.LTE to "#sigil:lte",
        TokenType.GTE to "#sigil:gte",
        TokenType.AND to "#sigil:and",
        TokenType.OR to "#sigil:or",
        TokenType.PLUS_PLUS to "#sigil:concat",
    )

    private val stdlibFnMap = mapOf(
        "abs" to "#sigil:abs",
        "min" to "#sigil:min",
        "max" to "#sigil:max",
        "str_length" to "#sigil:str_length",
        "str_upper" to "#sigil:str_upper",
        "str_lower" to "#sigil:str_lower",
    )

    private fun current(): Token = tokens[pos]
    private fun peek(): Token = tokens[pos]

    private fun peekType(): TokenType = tokens[pos].type

    private fun peekAhead(offset: Int): Token =
        if (pos + offset < tokens.size) tokens[pos + offset] else tokens.last()

    private fun advance(): Token {
        val t = tokens[pos]
        if (pos < tokens.size - 1) pos++
        return t
    }

    private fun expect(type: TokenType): Token {
        val t = current()
        if (t.type != type) {
            throw ParseError("Expected $type but got ${t.type} '${t.value}'", t.line, t.col)
        }
        return advance()
    }

    private fun match(type: TokenType): Boolean {
        if (current().type == type) {
            advance()
            return true
        }
        return false
    }

    private fun skipNewlines() {
        while (current().type == TokenType.NEWLINE) advance()
    }

    fun parseProgram(): List<Any> {
        val defs = mutableListOf<Any>()
        skipNewlines()
        while (current().type != TokenType.EOF) {
            defs.add(parseTopLevel())
            skipNewlines()
        }
        return defs
    }

    private fun parseTopLevel(): Any {
        skipNewlines()
        return when (current().type) {
            TokenType.FN -> parseFnDef()
            TokenType.TYPE -> parseTypeDef()
            TokenType.TRAIT -> parseTraitDef()
            TokenType.EFFECT -> parseEffectDef()
            TokenType.HANDLER -> parseHandlerDef()
            TokenType.MODULE -> parseModuleDef()
            TokenType.PROPERTY -> parseProperty()
            else -> throw ParseError("Expected top-level definition, got ${current().type} '${current().value}'", current().line, current().col)
        }
    }

    fun parseFnDef(): FnDef {
        expect(TokenType.FN)
        val name = expect(TokenType.IDENT).value
        val typeParams = parseOptionalTypeParams()
        expect(TokenType.LPAREN)
        val params = parseParamList()
        expect(TokenType.RPAREN)
        val returnType = if (match(TokenType.ARROW)) parseTypeRef() else TypeRef(PrimitiveTypes.UNIT)
        val effects = parseOptionalEffects()
        expect(TokenType.LBRACE)
        skipNewlines()

        // Parse contracts and body
        val requires = mutableListOf<ContractClause>()
        val ensures = mutableListOf<ContractClause>()
        while (current().type == TokenType.REQUIRES) {
            advance()
            requires.add(ContractClause(parseExpr()))
            skipNewlines()
        }
        while (current().type == TokenType.ENSURES) {
            advance()
            ensures.add(ContractClause(parseExpr()))
            skipNewlines()
        }

        val body = parseBlockBody()
        expect(TokenType.RBRACE)

        val contract = if (requires.isNotEmpty() || ensures.isNotEmpty()) {
            ContractNode(requires, ensures)
        } else null

        return FnDef(
            name = name,
            params = params,
            returnType = returnType,
            contract = contract,
            effects = effects,
            body = body,
        )
    }

    fun parseTypeDef(): TypeDef {
        expect(TokenType.TYPE)
        val name = expect(TokenType.IDENT).value
        val typeParams = parseOptionalTypeParams()
        expect(TokenType.LBRACE)
        skipNewlines()
        val variants = mutableListOf<Variant>()
        while (current().type == TokenType.PIPE) {
            advance()
            val variantName = expect(TokenType.IDENT).value
            val fields = if (current().type == TokenType.LPAREN) {
                advance()
                val ps = parseParamList()
                expect(TokenType.RPAREN)
                ps
            } else emptyList()
            variants.add(Variant(variantName, fields))
            skipNewlines()
        }
        expect(TokenType.RBRACE)
        return TypeDef(name = name, typeParams = typeParams, variants = variants)
    }

    private fun parseTraitDef(): TraitDef {
        expect(TokenType.TRAIT)
        val name = expect(TokenType.IDENT).value
        val typeParams = parseOptionalTypeParams()
        expect(TokenType.LBRACE)
        skipNewlines()
        val methods = mutableListOf<FnSignature>()
        val laws = mutableListOf<Property>()
        while (current().type != TokenType.RBRACE) {
            if (current().type == TokenType.FN) {
                advance()
                val mName = expect(TokenType.IDENT).value
                expect(TokenType.LPAREN)
                val mParams = parseParamList()
                expect(TokenType.RPAREN)
                val mReturn = if (match(TokenType.ARROW)) parseTypeRef() else TypeRef(PrimitiveTypes.UNIT)
                val mEffects = parseOptionalEffects()
                methods.add(FnSignature(mName, mParams, mReturn, mEffects))
            } else if (current().type == TokenType.PROPERTY) {
                laws.add(parseProperty())
            } else {
                throw ParseError("Expected fn or property in trait, got ${current().type}", current().line, current().col)
            }
            skipNewlines()
        }
        expect(TokenType.RBRACE)
        return TraitDef(name = name, methods = methods, laws = laws)
    }

    private fun parseEffectDef(): EffectDef {
        expect(TokenType.EFFECT)
        val name = expect(TokenType.IDENT).value
        expect(TokenType.LBRACE)
        skipNewlines()
        val ops = mutableListOf<EffectOperation>()
        while (current().type == TokenType.FN) {
            advance()
            val opName = expect(TokenType.IDENT).value
            expect(TokenType.LPAREN)
            val opParams = parseParamList()
            expect(TokenType.RPAREN)
            val opReturn = if (match(TokenType.ARROW)) parseTypeRef() else TypeRef(PrimitiveTypes.UNIT)
            ops.add(EffectOperation(opName, opParams, opReturn))
            skipNewlines()
        }
        expect(TokenType.RBRACE)
        return EffectDef(name = name, operations = ops)
    }

    private fun parseHandlerDef(): EffectHandler {
        expect(TokenType.HANDLER)
        val name = expect(TokenType.IDENT).value
        expect(TokenType.COLON)
        val handles = expect(TokenType.IDENT).value
        expect(TokenType.LBRACE)
        skipNewlines()
        val impls = mutableMapOf<Alias, ExprNode>()
        while (current().type == TokenType.FN) {
            advance()
            val implName = expect(TokenType.IDENT).value
            expect(TokenType.LPAREN)
            val implParams = parseParamList()
            expect(TokenType.RPAREN)
            val retType = if (match(TokenType.ARROW)) parseTypeRef() else null
            expect(TokenType.LBRACE)
            skipNewlines()
            val body = parseBlockBody()
            expect(TokenType.RBRACE)
            // Wrap as lambda
            impls[implName] = if (implParams.isNotEmpty()) {
                ExprNode.Lambda(implParams, body)
            } else body
            skipNewlines()
        }
        expect(TokenType.RBRACE)
        return EffectHandler(name = name, handles = handles, implementations = impls)
    }

    private fun parseModuleDef(): ModuleDef {
        expect(TokenType.MODULE)
        val name = expect(TokenType.IDENT).value
        expect(TokenType.LBRACE)
        skipNewlines()
        val exports = mutableListOf<Hash>()
        val definitions = mutableListOf<Hash>()
        while (current().type != TokenType.RBRACE) {
            if (current().type == TokenType.EXPORT) {
                advance()
                exports.add(expect(TokenType.IDENT).value)
            } else {
                definitions.add(expect(TokenType.IDENT).value)
            }
            skipNewlines()
        }
        expect(TokenType.RBRACE)
        return ModuleDef(name = name, exports = exports, definitions = definitions)
    }

    private fun parseProperty(): Property {
        expect(TokenType.PROPERTY)
        val quantVars = if (current().type == TokenType.LPAREN) {
            advance()
            val params = parseParamList()
            expect(TokenType.RPAREN)
            params
        } else emptyList()
        expect(TokenType.LBRACE)
        skipNewlines()
        val pred = parseExpr()
        skipNewlines()
        expect(TokenType.RBRACE)
        return Property(quantifiedVars = quantVars, predicate = pred)
    }

    private fun parseOptionalTypeParams(): List<TypeVar> {
        if (current().type != TokenType.LT) return emptyList()
        advance()
        val params = mutableListOf<TypeVar>()
        if (current().type != TokenType.GT) {
            params.add(parseTypeVar())
            while (match(TokenType.COMMA)) {
                params.add(parseTypeVar())
            }
        }
        expect(TokenType.GT)
        return params
    }

    private fun parseTypeVar(): TypeVar {
        val name = expect(TokenType.IDENT).value
        val bounds = if (match(TokenType.COLON)) {
            val b = mutableListOf<Hash>()
            b.add(expect(TokenType.IDENT).value)
            while (match(TokenType.PLUS)) {
                b.add(expect(TokenType.IDENT).value)
            }
            b
        } else emptyList()
        return TypeVar(name, bounds)
    }

    private fun parseOptionalEffects(): EffectSet {
        if (current().type != TokenType.BANG) return emptySet()
        advance()
        val effects = mutableSetOf<Hash>()
        effects.add(expect(TokenType.IDENT).value)
        while (match(TokenType.COMMA)) {
            // skip newlines between effect names in multi-line
            skipNewlines()
            effects.add(expect(TokenType.IDENT).value)
        }
        return effects
    }

    fun parseTypeRef(): TypeRef {
        if (current().type == TokenType.HASH_REF) {
            val hash = advance().value
            return TypeRef(defHash = hash)
        }
        if (current().type == TokenType.LPAREN) {
            // Check for unit type ()
            if (peekAhead(1).type == TokenType.RPAREN) {
                advance(); advance()
                return TypeRef(defHash = PrimitiveTypes.UNIT)
            }
        }
        val name = expect(TokenType.IDENT).value
        val hash = primitiveTypeMap[name] ?: name
        val args = if (current().type == TokenType.LT) {
            advance()
            val a = mutableListOf<TypeRef>()
            a.add(parseTypeRef())
            while (match(TokenType.COMMA)) a.add(parseTypeRef())
            expect(TokenType.GT)
            a
        } else emptyList()
        return TypeRef(defHash = hash, args = args)
    }

    private fun parseParamList(): List<Param> {
        val params = mutableListOf<Param>()
        skipNewlines()
        if (current().type == TokenType.RPAREN) return params
        params.add(parseParam())
        while (match(TokenType.COMMA)) {
            skipNewlines()
            if (current().type == TokenType.RPAREN) break
            params.add(parseParam())
        }
        return params
    }

    private fun parseParam(): Param {
        val name = expect(TokenType.IDENT).value
        expect(TokenType.COLON)
        val type = parseTypeRef()
        return Param(name, type)
    }

    private fun parseBlockBody(): ExprNode {
        skipNewlines()
        val exprs = mutableListOf<ExprNode>()
        while (current().type != TokenType.RBRACE) {
            exprs.add(parseExpr())
            skipNewlines()
        }
        return if (exprs.size == 1) exprs[0] else ExprNode.Block(exprs)
    }

    fun parseExpr(): ExprNode = parseLetOrExpr()

    private fun parseLetOrExpr(): ExprNode {
        if (current().type == TokenType.LET) {
            return parseLetExpr()
        }
        return parseOr()
    }

    private fun parseLetExpr(): ExprNode {
        expect(TokenType.LET)
        val name = expect(TokenType.IDENT).value
        // Optional type annotation
        if (match(TokenType.COLON)) {
            parseTypeRef() // consume type annotation (used for type checking later)
        }
        expect(TokenType.EQUALS)
        val value = parseExpr()
        skipNewlines()
        // If there's a following expression in a block, it becomes the body
        return if (current().type != TokenType.RBRACE && current().type != TokenType.EOF &&
            current().type != TokenType.COMMA && current().type != TokenType.RPAREN) {
            val body = parseExpr()
            ExprNode.Let(name, value, body)
        } else {
            // No body - use unit
            ExprNode.Let(name, value, ExprNode.Literal(LiteralValue.UnitLit))
        }
    }

    private fun parseOr(): ExprNode {
        var left = parseAnd()
        while (current().type == TokenType.OR) {
            val op = advance()
            val right = parseAnd()
            left = ExprNode.Apply(ExprNode.Ref(binaryOpMap[op.type]!!), listOf(left, right))
        }
        return left
    }

    private fun parseAnd(): ExprNode {
        var left = parseEquality()
        while (current().type == TokenType.AND) {
            val op = advance()
            val right = parseEquality()
            left = ExprNode.Apply(ExprNode.Ref(binaryOpMap[op.type]!!), listOf(left, right))
        }
        return left
    }

    private fun parseEquality(): ExprNode {
        var left = parseComparison()
        while (current().type in listOf(TokenType.EQ, TokenType.NEQ)) {
            val op = advance()
            val right = parseComparison()
            left = ExprNode.Apply(ExprNode.Ref(binaryOpMap[op.type]!!), listOf(left, right))
        }
        return left
    }

    private fun parseComparison(): ExprNode {
        var left = parseConcat()
        while (current().type in listOf(TokenType.LT, TokenType.GT, TokenType.LTE, TokenType.GTE)) {
            val op = advance()
            val right = parseConcat()
            left = ExprNode.Apply(ExprNode.Ref(binaryOpMap[op.type]!!), listOf(left, right))
        }
        return left
    }

    private fun parseConcat(): ExprNode {
        var left = parseAddSub()
        while (current().type == TokenType.PLUS_PLUS) {
            val op = advance()
            val right = parseAddSub()
            left = ExprNode.Apply(ExprNode.Ref(binaryOpMap[op.type]!!), listOf(left, right))
        }
        return left
    }

    private fun parseAddSub(): ExprNode {
        var left = parseMulDiv()
        while (current().type in listOf(TokenType.PLUS, TokenType.MINUS)) {
            val op = advance()
            val right = parseMulDiv()
            left = ExprNode.Apply(ExprNode.Ref(binaryOpMap[op.type]!!), listOf(left, right))
        }
        return left
    }

    private fun parseMulDiv(): ExprNode {
        var left = parseUnary()
        while (current().type in listOf(TokenType.STAR, TokenType.SLASH, TokenType.PERCENT)) {
            val op = advance()
            val right = parseUnary()
            left = ExprNode.Apply(ExprNode.Ref(binaryOpMap[op.type]!!), listOf(left, right))
        }
        return left
    }

    private fun parseUnary(): ExprNode {
        if (current().type == TokenType.MINUS) {
            advance()
            val expr = parseUnary()
            return ExprNode.Apply(ExprNode.Ref("#sigil:neg"), listOf(expr))
        }
        return parsePostfix()
    }

    private fun parsePostfix(): ExprNode {
        var expr = parsePrimary()
        while (true) {
            when (current().type) {
                TokenType.LPAREN -> {
                    // Function application
                    advance()
                    val args = parseArgList()
                    expect(TokenType.RPAREN)
                    expr = ExprNode.Apply(expr, args)
                }
                TokenType.DOT -> {
                    advance()
                    val field = expect(TokenType.IDENT).value
                    // Method call: desugar a.f(args) => f(a, args)
                    if (current().type == TokenType.LPAREN) {
                        advance()
                        val args = parseArgList()
                        expect(TokenType.RPAREN)
                        expr = ExprNode.Apply(ExprNode.Ref(field), listOf(expr) + args)
                    } else {
                        // Field access: desugar to field_access(expr, "field")
                        expr = ExprNode.Apply(ExprNode.Ref("#sigil:field"), listOf(expr, ExprNode.Literal(LiteralValue.StringLit(field))))
                    }
                }
                else -> break
            }
        }
        return expr
    }

    private fun parsePrimary(): ExprNode {
        return when (current().type) {
            TokenType.INT_LIT -> {
                val v = advance().value
                ExprNode.Literal(LiteralValue.IntLit(v.toLong()))
            }
            TokenType.FLOAT_LIT -> {
                val v = advance().value
                ExprNode.Literal(LiteralValue.FloatLit(v.toDouble()))
            }
            TokenType.STRING_LIT -> {
                val v = advance().value
                ExprNode.Literal(LiteralValue.StringLit(v))
            }
            TokenType.TRUE -> {
                advance()
                ExprNode.Literal(LiteralValue.BoolLit(true))
            }
            TokenType.FALSE -> {
                advance()
                ExprNode.Literal(LiteralValue.BoolLit(false))
            }
            TokenType.HASH_REF -> {
                val hash = advance().value
                ExprNode.Ref(hash)
            }
            TokenType.IF -> parseIfExpr()
            TokenType.MATCH -> parseMatchExpr()
            TokenType.PIPE -> parseLambda()
            TokenType.LBRACKET -> parseListLiteral()
            TokenType.LPAREN -> {
                advance()
                if (current().type == TokenType.RPAREN) {
                    advance()
                    ExprNode.Literal(LiteralValue.UnitLit)
                } else {
                    val expr = parseExpr()
                    expect(TokenType.RPAREN)
                    expr
                }
            }
            TokenType.IDENT -> {
                val name = advance().value
                // Only resolve stdlib names when used in function call position
                val resolved = if (current().type == TokenType.LPAREN && name in stdlibFnMap) {
                    stdlibFnMap[name]!!
                } else {
                    name
                }
                ExprNode.Ref(resolved)
            }
            else -> throw ParseError("Unexpected token ${current().type} '${current().value}'", current().line, current().col)
        }
    }

    private fun parseIfExpr(): ExprNode {
        expect(TokenType.IF)
        val cond = parseExpr()
        expect(TokenType.THEN)
        val thenExpr = parseExpr()
        expect(TokenType.ELSE)
        val elseExpr = parseExpr()
        return ExprNode.If(cond, thenExpr, elseExpr)
    }

    private fun parseMatchExpr(): ExprNode {
        expect(TokenType.MATCH)
        val scrutinee = parseExpr()
        expect(TokenType.LBRACE)
        skipNewlines()
        val arms = mutableListOf<MatchArm>()
        while (current().type != TokenType.RBRACE) {
            val pattern = parsePattern()
            expect(TokenType.FAT_ARROW)
            val body = parseExpr()
            arms.add(MatchArm(pattern, body))
            // Separator: comma or newline
            if (current().type == TokenType.COMMA) advance()
            skipNewlines()
        }
        expect(TokenType.RBRACE)
        return ExprNode.Match(scrutinee, arms)
    }

    private fun parsePattern(): Pattern {
        return when {
            current().type == TokenType.UNDERSCORE -> {
                advance()
                Pattern.WildcardPattern
            }
            current().type == TokenType.INT_LIT -> {
                val v = advance().value
                Pattern.LiteralPattern(LiteralValue.IntLit(v.toLong()))
            }
            current().type == TokenType.FLOAT_LIT -> {
                val v = advance().value
                Pattern.LiteralPattern(LiteralValue.FloatLit(v.toDouble()))
            }
            current().type == TokenType.STRING_LIT -> {
                val v = advance().value
                Pattern.LiteralPattern(LiteralValue.StringLit(v))
            }
            current().type == TokenType.TRUE -> {
                advance()
                Pattern.LiteralPattern(LiteralValue.BoolLit(true))
            }
            current().type == TokenType.FALSE -> {
                advance()
                Pattern.LiteralPattern(LiteralValue.BoolLit(false))
            }
            current().type == TokenType.IDENT -> {
                val name = advance().value
                if (name[0].isUpperCase() && current().type == TokenType.LPAREN) {
                    // Constructor pattern
                    advance()
                    val fields = mutableListOf<Pattern>()
                    if (current().type != TokenType.RPAREN) {
                        fields.add(parsePattern())
                        while (match(TokenType.COMMA)) {
                            fields.add(parsePattern())
                        }
                    }
                    expect(TokenType.RPAREN)
                    Pattern.ConstructorPattern(constructor = name, fields = fields)
                } else if (name[0].isUpperCase()) {
                    // Enum-like constructor with no fields
                    Pattern.ConstructorPattern(constructor = name, fields = emptyList())
                } else {
                    Pattern.BindingPattern(name)
                }
            }
            current().type == TokenType.LPAREN -> {
                // Tuple pattern
                advance()
                val elements = mutableListOf<Pattern>()
                if (current().type != TokenType.RPAREN) {
                    elements.add(parsePattern())
                    while (match(TokenType.COMMA)) {
                        elements.add(parsePattern())
                    }
                }
                expect(TokenType.RPAREN)
                Pattern.TuplePattern(elements)
            }
            current().type == TokenType.LBRACKET -> {
                // List pattern: [] for empty list, [a, b, ...] for elements
                advance()
                val elements = mutableListOf<Pattern>()
                if (current().type != TokenType.RBRACKET) {
                    elements.add(parsePattern())
                    while (match(TokenType.COMMA)) {
                        elements.add(parsePattern())
                    }
                }
                expect(TokenType.RBRACKET)
                Pattern.ConstructorPattern(constructor = "#sigil:list", fields = elements)
            }
            else -> throw ParseError("Expected pattern, got ${current().type} '${current().value}'", current().line, current().col)
        }
    }

    private fun parseLambda(): ExprNode {
        expect(TokenType.PIPE)
        val params = mutableListOf<Param>()
        if (current().type != TokenType.PIPE) {
            params.add(parseLambdaParam())
            while (match(TokenType.COMMA)) {
                params.add(parseLambdaParam())
            }
        }
        expect(TokenType.PIPE)
        if (match(TokenType.ARROW)) {
            // optional -> before body
        }
        val body = parseExpr()
        return ExprNode.Lambda(params, body)
    }

    private fun parseLambdaParam(): Param {
        val name = expect(TokenType.IDENT).value
        val type = if (match(TokenType.COLON)) parseTypeRef() else TypeRef(defHash = "#infer")
        return Param(name, type)
    }

    private fun parseListLiteral(): ExprNode {
        expect(TokenType.LBRACKET)
        val elements = mutableListOf<ExprNode>()
        skipNewlines()
        if (current().type != TokenType.RBRACKET) {
            elements.add(parseExpr())
            while (match(TokenType.COMMA)) {
                skipNewlines()
                if (current().type == TokenType.RBRACKET) break
                elements.add(parseExpr())
            }
        }
        skipNewlines()
        expect(TokenType.RBRACKET)
        return ExprNode.Apply(ExprNode.Ref("#sigil:list"), elements)
    }

    private fun parseArgList(): List<ExprNode> {
        val args = mutableListOf<ExprNode>()
        skipNewlines()
        if (current().type == TokenType.RPAREN) return args
        args.add(parseExpr())
        while (match(TokenType.COMMA)) {
            skipNewlines()
            if (current().type == TokenType.RPAREN) break
            args.add(parseExpr())
        }
        return args
    }
}

fun parse(source: String): List<Any> {
    val tokens = SigilLexer(source).tokenize()
    return SigilParser(tokens).parseProgram()
}
