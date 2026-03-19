package sigil.types

import sigil.ast.*

class TypeCheckError(message: String) : Exception(message)

class TypeChecker {
    private val env = mutableMapOf<String, Type>()
    private val typeDefs = mutableMapOf<String, TypeDef>()
    private val traitDefs = mutableMapOf<String, TraitDef>()
    val unifier = Unifier()
    val traitResolver = TraitResolver()

    fun registerPrimitives() {
        env[PrimitiveTypes.INT] = Type.Concrete(PrimitiveTypes.INT)
        env[PrimitiveTypes.INT32] = Type.Concrete(PrimitiveTypes.INT32)
        env[PrimitiveTypes.INT64] = Type.Concrete(PrimitiveTypes.INT64)
        env[PrimitiveTypes.FLOAT64] = Type.Concrete(PrimitiveTypes.FLOAT64)
        env[PrimitiveTypes.BOOL] = Type.Concrete(PrimitiveTypes.BOOL)
        env[PrimitiveTypes.STRING] = Type.Concrete(PrimitiveTypes.STRING)
        env[PrimitiveTypes.UNIT] = Type.Concrete(PrimitiveTypes.UNIT)
        env[PrimitiveTypes.BYTES] = Type.Concrete(PrimitiveTypes.BYTES)

        // Built-in arithmetic operators: (Int, Int) -> Int
        val intT = Type.Concrete(PrimitiveTypes.INT)
        val boolT = Type.Concrete(PrimitiveTypes.BOOL)
        val arithmeticOp = Type.Function(listOf(intT, intT), intT)
        val comparisonOp = Type.Function(listOf(intT, intT), boolT)
        val booleanOp = Type.Function(listOf(boolT, boolT), boolT)

        for (op in listOf("#sigil:add", "#sigil:sub", "#sigil:mul", "#sigil:div", "#sigil:mod")) {
            env[op] = arithmeticOp
        }
        for (op in listOf("#sigil:eq", "#sigil:neq", "#sigil:lt", "#sigil:gt", "#sigil:lte", "#sigil:gte")) {
            env[op] = comparisonOp
        }
        for (op in listOf("#sigil:and", "#sigil:or")) {
            env[op] = booleanOp
        }
        env["#sigil:neg"] = Type.Function(listOf(intT), intT)
        env["#sigil:not"] = Type.Function(listOf(boolT), boolT)
        env["#sigil:concat"] = Type.Function(
            listOf(Type.Concrete(PrimitiveTypes.STRING), Type.Concrete(PrimitiveTypes.STRING)),
            Type.Concrete(PrimitiveTypes.STRING)
        )

        // Stdlib math functions
        val stringT = Type.Concrete(PrimitiveTypes.STRING)
        env["#sigil:abs"] = Type.Function(listOf(intT), intT)
        env["#sigil:min"] = Type.Function(listOf(intT, intT), intT)
        env["#sigil:max"] = Type.Function(listOf(intT, intT), intT)

        // Stdlib string functions
        env["#sigil:str_length"] = Type.Function(listOf(stringT), intT)
        env["#sigil:str_upper"] = Type.Function(listOf(stringT), stringT)
        env["#sigil:str_lower"] = Type.Function(listOf(stringT), stringT)
    }

    fun registerTypeDef(td: TypeDef) {
        val hash = td.hash ?: return
        typeDefs[hash] = td
        for (variant in td.variants) {
            val variantHash = "$hash:${variant.name}"
            if (variant.fields.isEmpty()) {
                env[variantHash] = Type.Concrete(hash)
            } else {
                val paramTypes = variant.fields.map { unifier.typeRefToType(it.type) }
                env[variantHash] = Type.Function(paramTypes, Type.Concrete(hash))
            }
        }
    }

    fun registerTraitDef(td: TraitDef) {
        val hash = td.hash ?: return
        traitDefs[hash] = td
    }

    fun registerFnDef(fn: FnDef) {
        val hash = fn.hash ?: return
        val paramTypes = fn.params.map { unifier.typeRefToType(it.type) }
        val returnType = unifier.typeRefToType(fn.returnType)
        env[hash] = Type.Function(paramTypes, returnType, fn.effects)
    }

    fun registerBinding(name: String, type: Type) {
        env[name] = type
    }

    fun checkFnDef(fn: FnDef): Type {
        val paramTypes = fn.params.map { unifier.typeRefToType(it.type) }
        val declaredReturn = unifier.typeRefToType(fn.returnType)

        val localEnv = mutableMapOf<String, Type>()
        fn.params.zip(paramTypes).forEach { (param, type) ->
            localEnv[param.name] = type
        }

        val bodyType = inferExpr(fn.body, localEnv)
        try {
            unifier.unify(bodyType, declaredReturn)
        } catch (e: UnificationError) {
            throw TypeCheckError("Function '${fn.name}': body type does not match declared return type: ${e.message}")
        }

        if (fn.contract != null) {
            checkContract(fn.contract, localEnv, declaredReturn)
        }

        val fnType = Type.Function(paramTypes, declaredReturn, fn.effects)
        if (fn.hash != null) {
            env[fn.hash] = fnType
        }
        return fnType
    }

    fun inferExpr(expr: ExprNode, localEnv: Map<String, Type>): Type = when (expr) {
        is ExprNode.Literal -> inferLiteral(expr.value)
        is ExprNode.Ref -> lookupRef(expr.hash, localEnv)
        is ExprNode.Apply -> inferApply(expr, localEnv)
        is ExprNode.Let -> inferLet(expr, localEnv)
        is ExprNode.Lambda -> inferLambda(expr, localEnv)
        is ExprNode.If -> inferIf(expr, localEnv)
        is ExprNode.Match -> inferMatch(expr, localEnv)
        is ExprNode.Block -> inferBlock(expr, localEnv)
    }

    private fun inferLiteral(lit: LiteralValue): Type = when (lit) {
        is LiteralValue.IntLit -> Type.Concrete(PrimitiveTypes.INT)
        is LiteralValue.FloatLit -> Type.Concrete(PrimitiveTypes.FLOAT64)
        is LiteralValue.StringLit -> Type.Concrete(PrimitiveTypes.STRING)
        is LiteralValue.BoolLit -> Type.Concrete(PrimitiveTypes.BOOL)
        is LiteralValue.UnitLit -> Type.Concrete(PrimitiveTypes.UNIT)
    }

    private fun lookupRef(hash: String, localEnv: Map<String, Type>): Type {
        return localEnv[hash] ?: env[hash] ?: throw TypeCheckError("Undefined reference: $hash")
    }

    private fun inferApply(expr: ExprNode.Apply, localEnv: Map<String, Type>): Type {
        val fnType = inferExpr(expr.fn, localEnv)
        val resolved = unifier.resolve(fnType)

        val funcType = when (resolved) {
            is Type.Function -> resolved
            is Type.Variable -> {
                val paramTypes = expr.args.map { unifier.freshVar() as Type }
                val retType = unifier.freshVar()
                val expected = Type.Function(paramTypes, retType)
                unifier.unify(resolved, expected)
                expected
            }
            else -> throw TypeCheckError("Cannot apply non-function type: ${resolved}")
        }

        if (funcType.params.size != expr.args.size) {
            throw TypeCheckError("Argument count mismatch: expected ${funcType.params.size}, got ${expr.args.size}")
        }

        expr.args.zip(funcType.params).forEach { (argExpr, paramType) ->
            val argType = inferExpr(argExpr, localEnv)
            try {
                unifier.unify(argType, paramType)
            } catch (e: UnificationError) {
                throw TypeCheckError("Argument type mismatch: ${e.message}")
            }
        }

        return unifier.resolve(funcType.returnType)
    }

    private fun inferLet(expr: ExprNode.Let, localEnv: Map<String, Type>): Type {
        val valueType = inferExpr(expr.value, localEnv)
        val extendedEnv = localEnv.toMutableMap()
        extendedEnv[expr.binding] = valueType
        return inferExpr(expr.body, extendedEnv)
    }

    private fun inferLambda(expr: ExprNode.Lambda, localEnv: Map<String, Type>): Type {
        val paramTypes = expr.params.map { unifier.typeRefToType(it.type) }
        val extendedEnv = localEnv.toMutableMap()
        expr.params.zip(paramTypes).forEach { (param, type) ->
            extendedEnv[param.name] = type
        }
        val bodyType = inferExpr(expr.body, extendedEnv)
        return Type.Function(paramTypes, bodyType)
    }

    private fun inferIf(expr: ExprNode.If, localEnv: Map<String, Type>): Type {
        val condType = inferExpr(expr.cond, localEnv)
        try {
            unifier.unify(condType, Type.Concrete(PrimitiveTypes.BOOL))
        } catch (e: UnificationError) {
            throw TypeCheckError("If condition must be Bool, got: ${e.message}")
        }

        val thenType = inferExpr(expr.then_, localEnv)
        val elseType = inferExpr(expr.else_, localEnv)
        try {
            unifier.unify(thenType, elseType)
        } catch (e: UnificationError) {
            throw TypeCheckError("If branches must have same type: ${e.message}")
        }

        return unifier.resolve(thenType)
    }

    private fun inferMatch(expr: ExprNode.Match, localEnv: Map<String, Type>): Type {
        val scrutineeType = inferExpr(expr.scrutinee, localEnv)

        if (expr.arms.isEmpty()) {
            throw TypeCheckError("Match expression must have at least one arm")
        }

        val resultType = unifier.freshVar() as Type

        for (arm in expr.arms) {
            val armEnv = localEnv.toMutableMap()
            checkPattern(arm.pattern, scrutineeType, armEnv)
            val armType = inferExpr(arm.body, armEnv)
            try {
                unifier.unify(resultType, armType)
            } catch (e: UnificationError) {
                throw TypeCheckError("Match arm types must be consistent: ${e.message}")
            }
        }

        return unifier.resolve(resultType)
    }

    private fun checkPattern(pattern: Pattern, expectedType: Type, env: MutableMap<String, Type>) {
        when (pattern) {
            is Pattern.WildcardPattern -> {}
            is Pattern.BindingPattern -> {
                env[pattern.name] = expectedType
            }
            is Pattern.LiteralPattern -> {
                val litType = inferLiteral(pattern.value)
                try {
                    unifier.unify(litType, expectedType)
                } catch (e: UnificationError) {
                    throw TypeCheckError("Pattern literal type mismatch: ${e.message}")
                }
            }
            is Pattern.ConstructorPattern -> {
                val ctorType = this.env[pattern.constructor]
                    ?: throw TypeCheckError("Unknown constructor: ${pattern.constructor}")
                val resolved = unifier.resolve(ctorType)
                when (resolved) {
                    is Type.Function -> {
                        try {
                            unifier.unify(resolved.returnType, expectedType)
                        } catch (e: UnificationError) {
                            throw TypeCheckError("Constructor pattern type mismatch: ${e.message}")
                        }
                        if (pattern.fields.size != resolved.params.size) {
                            throw TypeCheckError("Constructor field count mismatch: expected ${resolved.params.size}, got ${pattern.fields.size}")
                        }
                        pattern.fields.zip(resolved.params).forEach { (fieldPat, fieldType) ->
                            checkPattern(fieldPat, fieldType, env)
                        }
                    }
                    is Type.Concrete -> {
                        try {
                            unifier.unify(resolved, expectedType)
                        } catch (e: UnificationError) {
                            throw TypeCheckError("Constructor pattern type mismatch: ${e.message}")
                        }
                        if (pattern.fields.isNotEmpty()) {
                            throw TypeCheckError("Constructor takes no fields but pattern has ${pattern.fields.size}")
                        }
                    }
                    else -> throw TypeCheckError("Constructor reference is not a valid type")
                }
            }
            is Pattern.TuplePattern -> {
                // Tuples are not yet supported as a first-class type
                throw TypeCheckError("Tuple patterns are not yet supported")
            }
        }
    }

    private fun inferBlock(expr: ExprNode.Block, localEnv: Map<String, Type>): Type {
        if (expr.exprs.isEmpty()) {
            return Type.Concrete(PrimitiveTypes.UNIT)
        }
        var lastType: Type = Type.Concrete(PrimitiveTypes.UNIT)
        for (e in expr.exprs) {
            lastType = inferExpr(e, localEnv)
        }
        return lastType
    }

    private fun checkContract(contract: ContractNode, localEnv: Map<String, Type>, returnType: Type? = null) {
        for (clause in contract.requires) {
            val predType = inferExpr(clause.predicate, localEnv)
            try {
                unifier.unify(predType, Type.Concrete(PrimitiveTypes.BOOL))
            } catch (e: UnificationError) {
                throw TypeCheckError("Contract requires clause must return Bool: ${e.message}")
            }
        }
        // 'result' refers to the function's return value in ensures clauses
        val ensuresEnv = if (returnType != null) {
            localEnv.toMutableMap().also { it["result"] = returnType }
        } else {
            localEnv
        }
        for (clause in contract.ensures) {
            val predType = inferExpr(clause.predicate, ensuresEnv)
            try {
                unifier.unify(predType, Type.Concrete(PrimitiveTypes.BOOL))
            } catch (e: UnificationError) {
                throw TypeCheckError("Contract ensures clause must return Bool: ${e.message}")
            }
        }
    }
}
