package sigil.contracts

import sigil.ast.*
import sigil.types.*

class ContractError(message: String) : Exception(message)

class ContractVerifier(private val typeChecker: TypeChecker) {

    /**
     * Verify a function's contracts are well-formed:
     * 1. All requires predicates reference only input params and return Bool
     * 2. All ensures predicates can reference 'result' + input params and return Bool
     * 3. Predicates are type-correct
     */
    fun verifyContracts(fn: FnDef): List<ContractError> {
        val errors = mutableListOf<ContractError>()
        val contract = fn.contract ?: return errors

        val paramEnv = mutableMapOf<String, Type>()
        for (param in fn.params) {
            paramEnv[param.name] = typeChecker.unifier.typeRefToType(param.type)
        }

        for (clause in contract.requires) {
            try {
                val predType = typeChecker.inferExpr(clause.predicate, paramEnv)
                val resolved = typeChecker.unifier.resolve(predType)
                if (resolved != Type.Concrete(PrimitiveTypes.BOOL)) {
                    errors.add(ContractError("Requires predicate must return Bool, got: $resolved"))
                }
            } catch (e: TypeCheckError) {
                errors.add(ContractError("Requires predicate type error: ${e.message}"))
            }
        }

        val ensuresEnv = paramEnv.toMutableMap()
        ensuresEnv["result"] = typeChecker.unifier.typeRefToType(fn.returnType)

        for (clause in contract.ensures) {
            try {
                val predType = typeChecker.inferExpr(clause.predicate, ensuresEnv)
                val resolved = typeChecker.unifier.resolve(predType)
                if (resolved != Type.Concrete(PrimitiveTypes.BOOL)) {
                    errors.add(ContractError("Ensures predicate must return Bool, got: $resolved"))
                }
            } catch (e: TypeCheckError) {
                errors.add(ContractError("Ensures predicate type error: ${e.message}"))
            }
        }

        return errors
    }

    /**
     * Generate runtime assertion code for a requires contract clause.
     * Returns an ExprNode that checks the predicate and returns Unit if true,
     * or represents a contract violation if false.
     */
    fun generateRequiresCheck(clause: ContractClause, params: List<Param>): ExprNode {
        return ExprNode.If(
            cond = clause.predicate,
            then_ = ExprNode.Literal(LiteralValue.UnitLit),
            else_ = ExprNode.Literal(LiteralValue.StringLit("Contract violation: requires clause failed"))
        )
    }

    /**
     * Generate runtime assertion code for an ensures contract clause.
     * The resultBinding name is bound to the function's return value.
     */
    fun generateEnsuresCheck(clause: ContractClause, params: List<Param>, resultBinding: Alias): ExprNode {
        return ExprNode.Let(
            binding = resultBinding,
            value = ExprNode.Ref(resultBinding),
            body = ExprNode.If(
                cond = clause.predicate,
                then_ = ExprNode.Literal(LiteralValue.UnitLit),
                else_ = ExprNode.Literal(LiteralValue.StringLit("Contract violation: ensures clause failed"))
            )
        )
    }

    /**
     * Check if fnA's ensures clauses satisfy fnB's requires clauses.
     * For MVP: structural comparison of predicate expressions.
     */
    fun checkContractChaining(fnA: FnDef, fnB: FnDef): ContractChainResult {
        val ensuresClauses = fnA.contract?.ensures ?: emptyList()
        val requiresClauses = fnB.contract?.requires ?: emptyList()

        if (requiresClauses.isEmpty()) {
            return ContractChainResult(
                satisfied = emptyList(),
                unsatisfied = emptyList(),
                safe = true
            )
        }

        val satisfied = mutableListOf<Pair<ContractClause, ContractClause>>()
        val matchedRequires = mutableSetOf<Int>()

        for (reqIdx in requiresClauses.indices) {
            val req = requiresClauses[reqIdx]
            for (ens in ensuresClauses) {
                if (structurallyMatches(ens.predicate, req.predicate)) {
                    satisfied.add(ens to req)
                    matchedRequires.add(reqIdx)
                    break
                }
            }
        }

        val unsatisfied = requiresClauses.filterIndexed { idx, _ -> idx !in matchedRequires }

        return ContractChainResult(
            satisfied = satisfied,
            unsatisfied = unsatisfied,
            safe = unsatisfied.isEmpty()
        )
    }

    private fun structurallyMatches(a: ExprNode, b: ExprNode): Boolean = when {
        a is ExprNode.Literal && b is ExprNode.Literal -> a.value == b.value
        a is ExprNode.Ref && b is ExprNode.Ref -> a.hash == b.hash
        a is ExprNode.Apply && b is ExprNode.Apply -> {
            structurallyMatches(a.fn, b.fn) &&
                a.args.size == b.args.size &&
                a.args.zip(b.args).all { (aa, bb) -> structurallyMatches(aa, bb) }
        }
        a is ExprNode.If && b is ExprNode.If -> {
            structurallyMatches(a.cond, b.cond) &&
                structurallyMatches(a.then_, b.then_) &&
                structurallyMatches(a.else_, b.else_)
        }
        a is ExprNode.Let && b is ExprNode.Let -> {
            a.binding == b.binding &&
                structurallyMatches(a.value, b.value) &&
                structurallyMatches(a.body, b.body)
        }
        a is ExprNode.Lambda && b is ExprNode.Lambda -> {
            a.params == b.params && structurallyMatches(a.body, b.body)
        }
        a is ExprNode.Block && b is ExprNode.Block -> {
            a.exprs.size == b.exprs.size &&
                a.exprs.zip(b.exprs).all { (aa, bb) -> structurallyMatches(aa, bb) }
        }
        a is ExprNode.Match && b is ExprNode.Match -> {
            structurallyMatches(a.scrutinee, b.scrutinee) &&
                a.arms.size == b.arms.size &&
                a.arms.zip(b.arms).all { (aa, bb) ->
                    aa.pattern == bb.pattern && structurallyMatches(aa.body, bb.body)
                }
        }
        else -> false
    }
}

data class ContractChainResult(
    val satisfied: List<Pair<ContractClause, ContractClause>>,
    val unsatisfied: List<ContractClause>,
    val safe: Boolean
)
