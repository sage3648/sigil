package sigil.registry.smt

import sigil.ast.*

class SmtEncoder {

    private val opMap = mapOf(
        "#sigil:add" to "+",
        "#sigil:sub" to "-",
        "#sigil:mul" to "*",
        "#sigil:div" to "div",
        "#sigil:lt" to "<",
        "#sigil:gt" to ">",
        "#sigil:lte" to "<=",
        "#sigil:gte" to ">=",
        "#sigil:eq" to "=",
        "#sigil:and" to "and",
        "#sigil:or" to "or",
        "#sigil:not" to "not",
    )

    fun encodeContract(fn: FnDef): String {
        val sb = StringBuilder()
        sb.appendLine("; SMT-LIB2 encoding for function: ${fn.name}")
        sb.appendLine("(set-logic QF_LIA)")

        // Declare parameters
        for (param in fn.params) {
            val sort = typeRefToSort(param.type)
            sb.appendLine("(declare-const ${param.name} $sort)")
        }

        // Declare special result variable for ensures clauses
        val resultSort = typeRefToSort(fn.returnType)
        sb.appendLine("(declare-const result $resultSort)")

        val contract = fn.contract ?: return sb.apply {
            appendLine("(check-sat)")
        }.toString()

        // Assert requires clauses as preconditions
        for (clause in contract.requires) {
            val encoded = encodeExpr(clause.predicate)
            sb.appendLine("(assert $encoded)")
        }

        // For ensures: negate each clause, check UNSAT to prove
        // We encode ensures as: assume requires, assert (not ensures), check-sat
        // UNSAT means ensures is proved
        if (contract.ensures.isNotEmpty()) {
            val negatedEnsures = contract.ensures.map { clause ->
                "(not ${encodeExpr(clause.predicate)})"
            }
            val combined = if (negatedEnsures.size == 1) {
                negatedEnsures[0]
            } else {
                "(or ${negatedEnsures.joinToString(" ")})"
            }
            sb.appendLine("(assert $combined)")
        }

        sb.appendLine("(check-sat)")
        return sb.toString()
    }

    fun encodeChaining(fnA: FnDef, fnB: FnDef): String {
        val sb = StringBuilder()
        sb.appendLine("; SMT-LIB2 chaining check: ${fnA.name}.ensures => ${fnB.name}.requires")
        sb.appendLine("(set-logic QF_LIA)")

        // Declare all parameters from both functions
        val allParams = mutableSetOf<String>()
        for (param in fnA.params) {
            allParams.add(param.name)
            sb.appendLine("(declare-const ${param.name} ${typeRefToSort(param.type)})")
        }
        for (param in fnB.params) {
            if (param.name !in allParams) {
                allParams.add(param.name)
                sb.appendLine("(declare-const ${param.name} ${typeRefToSort(param.type)})")
            }
        }

        // Declare result variable (output of fnA / input-related to fnB)
        sb.appendLine("(declare-const result ${typeRefToSort(fnA.returnType)})")

        // Assert fnA's ensures as assumptions
        val ensuresA = fnA.contract?.ensures ?: emptyList()
        for (clause in ensuresA) {
            sb.appendLine("(assert ${encodeExpr(clause.predicate)})")
        }

        // Assert fnA's requires as assumptions (context)
        val requiresA = fnA.contract?.requires ?: emptyList()
        for (clause in requiresA) {
            sb.appendLine("(assert ${encodeExpr(clause.predicate)})")
        }

        // Negate fnB's requires - if UNSAT, the chaining is valid
        val requiresB = fnB.contract?.requires ?: emptyList()
        if (requiresB.isNotEmpty()) {
            val negated = requiresB.map { "(not ${encodeExpr(it.predicate)})" }
            val combined = if (negated.size == 1) negated[0] else "(or ${negated.joinToString(" ")})"
            sb.appendLine("(assert $combined)")
        }

        sb.appendLine("(check-sat)")
        return sb.toString()
    }

    fun encodeExpr(expr: ExprNode, bindings: Map<String, String> = emptyMap()): String {
        return when (expr) {
            is ExprNode.Literal -> encodeLiteral(expr.value)

            is ExprNode.Ref -> {
                val hash = expr.hash
                // Check if it's a known operator
                val op = opMap[hash]
                if (op != null) op
                // Check if it's a variable binding
                else bindings[hash] ?: hash.removePrefix("#sigil:").replace(Regex("[^a-zA-Z0-9_]"), "_")
            }

            is ExprNode.Apply -> {
                val fnExpr = expr.fn
                val args = expr.args

                if (fnExpr is ExprNode.Ref) {
                    val op = opMap[fnExpr.hash]
                    if (op != null) {
                        val encodedArgs = args.map { encodeExpr(it, bindings) }
                        return "($op ${encodedArgs.joinToString(" ")})"
                    }
                }

                // Generic function application - encode as uninterpreted
                val fnEncoded = encodeExpr(fnExpr, bindings)
                val argsEncoded = args.map { encodeExpr(it, bindings) }
                "($fnEncoded ${argsEncoded.joinToString(" ")})"
            }

            is ExprNode.Let -> {
                val boundValue = encodeExpr(expr.value, bindings)
                val newBindings = bindings + (expr.binding to boundValue)
                encodeExpr(expr.body, newBindings)
            }

            is ExprNode.If -> {
                val cond = encodeExpr(expr.cond, bindings)
                val then = encodeExpr(expr.then_, bindings)
                val else_ = encodeExpr(expr.else_, bindings)
                "(ite $cond $then $else_)"
            }

            is ExprNode.Lambda -> {
                // Lambdas can't be directly encoded in QF_LIA; return placeholder
                "unknown_lambda"
            }

            is ExprNode.Match -> {
                // Match expressions are complex; return placeholder
                "unknown_match"
            }

            is ExprNode.Block -> {
                // Encode last expression in block
                if (expr.exprs.isEmpty()) "true"
                else encodeExpr(expr.exprs.last(), bindings)
            }
        }
    }

    private fun encodeLiteral(value: LiteralValue): String = when (value) {
        is LiteralValue.IntLit -> if (value.value >= 0) value.value.toString() else "(- ${-value.value})"
        is LiteralValue.FloatLit -> value.value.toString()
        is LiteralValue.BoolLit -> value.value.toString()
        is LiteralValue.StringLit -> "\"${value.value}\""
        is LiteralValue.UnitLit -> "true"
    }

    private fun typeRefToSort(typeRef: TypeRef): String = when (typeRef.defHash) {
        PrimitiveTypes.INT, PrimitiveTypes.INT32, PrimitiveTypes.INT64 -> "Int"
        PrimitiveTypes.BOOL -> "Bool"
        PrimitiveTypes.FLOAT64 -> "Real"
        PrimitiveTypes.STRING -> "String"
        else -> "Int" // default fallback
    }
}
