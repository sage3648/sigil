package sigil.registry.semantic

import sigil.ast.*
import sigil.registry.store.SemanticSignature

class SemanticExtractor {

    fun extractSignature(fn: FnDef): SemanticSignature {
        val inputTypes = fn.params.map { serializeTypeRef(it.type) }
        val outputType = serializeTypeRef(fn.returnType)
        val effects = fn.effects.toList()
        val requires = fn.contract?.requires?.map { serializeExpr(it.predicate) } ?: emptyList()
        val ensures = fn.contract?.ensures?.map { serializeExpr(it.predicate) } ?: emptyList()

        return SemanticSignature(
            inputTypes = inputTypes,
            outputType = outputType,
            effects = effects,
            requiresPredicates = requires,
            ensuresPredicates = ensures
        )
    }

    fun extractSignature(td: TypeDef): SemanticSignature {
        val variantDescs = td.variants.map { variant ->
            val fields = variant.fields.joinToString(", ") { "${it.name}: ${serializeTypeRef(it.type)}" }
            "${variant.name}($fields)"
        }
        return SemanticSignature(
            inputTypes = emptyList(),
            outputType = td.name,
            effects = emptyList(),
            requiresPredicates = emptyList(),
            ensuresPredicates = variantDescs
        )
    }

    fun extractSignature(td: TraitDef): SemanticSignature {
        val methodSigs = td.methods.map { method ->
            val params = method.params.joinToString(", ") { serializeTypeRef(it.type) }
            val effects = if (method.effects.isNotEmpty()) " ! ${method.effects.joinToString(", ")}" else ""
            "${method.name}($params) -> ${serializeTypeRef(method.returnType)}$effects"
        }
        return SemanticSignature(
            inputTypes = emptyList(),
            outputType = td.name,
            effects = emptyList(),
            requiresPredicates = emptyList(),
            ensuresPredicates = methodSigs
        )
    }

    companion object {
        fun serializeTypeRef(ref: TypeRef): String {
            return if (ref.args.isEmpty()) {
                ref.defHash
            } else {
                "${ref.defHash}<${ref.args.joinToString(", ") { serializeTypeRef(it) }}>"
            }
        }

        fun serializeExpr(expr: ExprNode): String = when (expr) {
            is ExprNode.Literal -> serializeLiteral(expr.value)
            is ExprNode.Ref -> expr.hash
            is ExprNode.Apply -> {
                val fn = serializeExpr(expr.fn)
                val args = expr.args.joinToString(", ") { serializeExpr(it) }
                "$fn($args)"
            }
            is ExprNode.If -> "if(${serializeExpr(expr.cond)}, ${serializeExpr(expr.then_)}, ${serializeExpr(expr.else_)})"
            is ExprNode.Let -> "let ${expr.binding} = ${serializeExpr(expr.value)} in ${serializeExpr(expr.body)}"
            is ExprNode.Lambda -> {
                val params = expr.params.joinToString(", ") { it.name }
                "($params) -> ${serializeExpr(expr.body)}"
            }
            is ExprNode.Match -> "match(${serializeExpr(expr.scrutinee)})"
            is ExprNode.Block -> expr.exprs.joinToString("; ") { serializeExpr(it) }
        }

        private fun serializeLiteral(value: LiteralValue): String = when (value) {
            is LiteralValue.IntLit -> value.value.toString()
            is LiteralValue.FloatLit -> value.value.toString()
            is LiteralValue.StringLit -> "\"${value.value}\""
            is LiteralValue.BoolLit -> value.value.toString()
            is LiteralValue.UnitLit -> "()"
        }
    }
}
