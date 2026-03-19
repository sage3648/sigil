package sigil.effects

import sigil.ast.*

class EffectError(message: String) : Exception(message)

class EffectChecker {
    private val effectDefs = mutableMapOf<Hash, EffectDef>()
    private val fnEffects = mutableMapOf<Hash, EffectSet>()
    // Maps function hash -> set of effect hashes handled within that function
    private val fnHandlers = mutableMapOf<Hash, MutableSet<Hash>>()

    fun registerEffect(effect: EffectDef) {
        val hash = effect.hash ?: return
        effectDefs[hash] = effect
    }

    fun registerFn(hash: Hash, effects: EffectSet) {
        fnEffects[hash] = effects
    }

    fun registerHandler(fnHash: Hash, handler: EffectHandler) {
        fnHandlers.getOrPut(fnHash) { mutableSetOf() }.add(handler.handles)
    }

    fun checkFnDef(fn: FnDef): EffectSet {
        val actualEffects = collectEffects(fn.body)
        val handledEffects = fn.hash?.let { fnHandlers[it] } ?: emptySet()
        val unhandledEffects = actualEffects - handledEffects
        verifyEffects(fn.effects, unhandledEffects, fn.name)
        return unhandledEffects
    }

    fun collectEffects(expr: ExprNode): EffectSet = when (expr) {
        is ExprNode.Literal -> emptySet()

        is ExprNode.Ref -> fnEffects[expr.hash] ?: emptySet()

        is ExprNode.Apply -> {
            val fnEff = collectEffects(expr.fn)
            val argEff = expr.args.flatMap { collectEffects(it) }.toSet()
            fnEff + argEff
        }

        is ExprNode.Let -> collectEffects(expr.value) + collectEffects(expr.body)

        is ExprNode.Block -> expr.exprs.flatMap { collectEffects(it) }.toSet()

        is ExprNode.If ->
            collectEffects(expr.cond) + collectEffects(expr.then_) + collectEffects(expr.else_)

        is ExprNode.Match -> {
            val scrutineeEffects = collectEffects(expr.scrutinee)
            val armEffects = expr.arms.flatMap { collectEffects(it.body) }.toSet()
            scrutineeEffects + armEffects
        }

        is ExprNode.Lambda -> collectEffects(expr.body)
    }

    fun verifyEffects(declaredEffects: EffectSet, actualEffects: EffectSet, fnName: String) {
        val undeclared = actualEffects - declaredEffects
        if (undeclared.isNotEmpty()) {
            throw EffectError(
                "Function '$fnName' uses undeclared effects: ${undeclared.joinToString(", ")}"
            )
        }
    }
}
