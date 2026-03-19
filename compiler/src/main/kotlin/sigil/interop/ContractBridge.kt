package sigil.interop

import sigil.ast.*
import sigil.codegen.jvm.ContractViolation

enum class EnforcementMode { STRICT, WARN, MONITOR }

class ContractBridge(private val mode: EnforcementMode = EnforcementMode.STRICT) {

    fun checkRequires(fn: FnDef, args: Array<out Any?>) {
        val contract = fn.contract ?: return
        val paramBindings = fn.params.zip(args).associate { (param, arg) -> param.name to arg }

        for (clause in contract.requires) {
            val result = evaluatePredicate(clause.predicate, paramBindings)
            if (result != null && !result) {
                handleViolation("Requires contract violated for '${fn.name}'")
            }
        }
    }

    fun checkEnsures(fn: FnDef, result: Any?) {
        val contract = fn.contract ?: return
        val bindings = mapOf("result" to result)

        for (clause in contract.ensures) {
            val evalResult = evaluatePredicate(clause.predicate, bindings)
            if (evalResult != null && !evalResult) {
                handleViolation("Ensures contract violated for '${fn.name}'")
            }
        }
    }

    private fun handleViolation(message: String) {
        when (mode) {
            EnforcementMode.STRICT -> throw ContractViolation(message)
            EnforcementMode.WARN -> System.err.println("WARNING: $message")
            EnforcementMode.MONITOR -> { /* silently record */ }
        }
    }

    private fun evaluatePredicate(expr: ExprNode, bindings: Map<String, Any?>): Boolean? {
        return when (expr) {
            is ExprNode.Apply -> evaluateApply(expr, bindings)
            is ExprNode.Literal -> when (val v = expr.value) {
                is LiteralValue.BoolLit -> v.value
                else -> null
            }
            else -> null
        }
    }

    private fun evaluateApply(expr: ExprNode.Apply, bindings: Map<String, Any?>): Boolean? {
        val fn = expr.fn
        if (fn !is ExprNode.Ref || expr.args.size != 2) return null

        val left = resolveValue(expr.args[0], bindings) ?: return null
        val right = resolveValue(expr.args[1], bindings) ?: return null

        if (left is Long && right is Long) {
            return when (fn.hash) {
                "#sigil:gt" -> left > right
                "#sigil:lt" -> left < right
                "#sigil:eq" -> left == right
                "#sigil:neq" -> left != right
                "#sigil:gte" -> left >= right
                "#sigil:lte" -> left <= right
                else -> null
            }
        }
        return null
    }

    private fun resolveValue(expr: ExprNode, bindings: Map<String, Any?>): Any? {
        return when (expr) {
            is ExprNode.Ref -> bindings[expr.hash]
            is ExprNode.Literal -> when (val v = expr.value) {
                is LiteralValue.IntLit -> v.value
                is LiteralValue.FloatLit -> v.value
                is LiteralValue.BoolLit -> v.value
                is LiteralValue.StringLit -> v.value
                is LiteralValue.UnitLit -> Unit
            }
            else -> null
        }
    }
}
