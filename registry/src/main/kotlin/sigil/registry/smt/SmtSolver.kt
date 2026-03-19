package sigil.registry.smt

import kotlinx.serialization.Serializable
import sigil.ast.*

enum class SmtResult { SAT, UNSAT, UNKNOWN }

@Serializable
data class VerificationResult(
    val result: SmtResult,
    val provedClauses: List<String>,
    val unprovedClauses: List<String>,
    val smtOutput: String? = null
)

interface SmtSolver {
    suspend fun verify(smtProgram: String): SmtResult
    suspend fun verifyContracts(fn: FnDef): VerificationResult
    suspend fun verifyChaining(fnA: FnDef, fnB: FnDef): VerificationResult
}

class SimpleSmtSolver : SmtSolver {
    private val encoder = SmtEncoder()

    override suspend fun verify(smtProgram: String): SmtResult {
        // Parse and evaluate basic SMT programs
        return try {
            val assertions = parseAssertions(smtProgram)
            if (assertions.any { it.contains("unknown_lambda") || it.contains("unknown_match") }) {
                return SmtResult.UNKNOWN
            }
            evaluateAssertions(assertions)
        } catch (_: Exception) {
            SmtResult.UNKNOWN
        }
    }

    override suspend fun verifyContracts(fn: FnDef): VerificationResult {
        val contract = fn.contract
            ?: return VerificationResult(SmtResult.SAT, emptyList(), emptyList())

        val proved = mutableListOf<String>()
        val unproved = mutableListOf<String>()

        // Verify each ensures clause individually
        for ((index, clause) in contract.ensures.withIndex()) {
            val clauseLabel = "ensures[$index]"
            val singleEnsureContract = ContractNode(
                requires = contract.requires,
                ensures = listOf(clause)
            )
            val singleFn = fn.copy(contract = singleEnsureContract)
            val smtProgram = encoder.encodeContract(singleFn)
            val result = verify(smtProgram)

            when (result) {
                SmtResult.UNSAT -> proved.add(clauseLabel)
                else -> unproved.add(clauseLabel)
            }
        }

        val overallResult = when {
            unproved.isEmpty() && proved.isNotEmpty() -> SmtResult.UNSAT // all proved
            unproved.isNotEmpty() -> SmtResult.SAT // some not proved
            else -> SmtResult.SAT
        }

        return VerificationResult(
            result = overallResult,
            provedClauses = proved,
            unprovedClauses = unproved,
            smtOutput = encoder.encodeContract(fn)
        )
    }

    override suspend fun verifyChaining(fnA: FnDef, fnB: FnDef): VerificationResult {
        val smtProgram = encoder.encodeChaining(fnA, fnB)
        val result = verify(smtProgram)

        val requiresB = fnB.contract?.requires ?: emptyList()
        val proved = mutableListOf<String>()
        val unproved = mutableListOf<String>()

        if (result == SmtResult.UNSAT) {
            requiresB.forEachIndexed { i, _ -> proved.add("requires[$i]") }
        } else {
            requiresB.forEachIndexed { i, _ -> unproved.add("requires[$i]") }
        }

        return VerificationResult(
            result = result,
            provedClauses = proved,
            unprovedClauses = unproved,
            smtOutput = smtProgram
        )
    }

    private fun parseAssertions(smtProgram: String): List<String> {
        val assertions = mutableListOf<String>()
        val lines = smtProgram.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("(assert ")) {
                // Extract the expression inside (assert ...)
                val expr = trimmed.removePrefix("(assert ").removeSuffix(")")
                assertions.add(expr)
            }
        }
        return assertions
    }

    private fun evaluateAssertions(assertions: List<String>): SmtResult {
        if (assertions.isEmpty()) return SmtResult.SAT

        // Try to find a simple contradiction in the assertions
        // This handles basic cases like: x > 0 AND NOT(x > 0) => UNSAT
        // Or: x > 0 AND NOT(x != 0) => UNSAT (because x > 0 implies x != 0)

        val positives = mutableListOf<SmtExpr>()
        val negatives = mutableListOf<SmtExpr>()

        for (assertion in assertions) {
            val parsed = parseSmtExpr(assertion) ?: return SmtResult.UNKNOWN
            collectPosNeg(parsed, positives, negatives)
        }

        // Check if negatives are implied by positives
        for (neg in negatives) {
            if (isImplied(neg, positives)) {
                return SmtResult.UNSAT
            }
        }

        // Check for direct contradictions
        for (pos in positives) {
            for (neg in negatives) {
                if (pos == neg) return SmtResult.UNSAT
            }
        }

        return SmtResult.UNKNOWN
    }

    private fun collectPosNeg(expr: SmtExpr, positives: MutableList<SmtExpr>, negatives: MutableList<SmtExpr>) {
        when {
            expr is SmtExpr.Not -> negatives.add(expr.inner)
            expr is SmtExpr.Or -> {
                // (or (not A) (not B)) means both A and B are negated
                val allNegated = expr.args.all { it is SmtExpr.Not }
                if (allNegated) {
                    for (arg in expr.args) {
                        negatives.add((arg as SmtExpr.Not).inner)
                    }
                } else {
                    // Can't easily decompose mixed or
                    positives.add(expr)
                }
            }
            else -> positives.add(expr)
        }
    }

    private fun isImplied(target: SmtExpr, assumptions: List<SmtExpr>): Boolean {
        // Direct match
        if (target in assumptions) return true

        // x > 0 implies x != 0 (i.e., NOT (= x 0))
        // x > 0 implies x >= 0
        // x >= 1 implies x > 0
        for (assumption in assumptions) {
            if (impliesRelation(assumption, target)) return true
        }

        return false
    }

    private fun impliesRelation(from: SmtExpr, to: SmtExpr): Boolean {
        // from: (> x 0) => to: (not (= x 0)) -- x > 0 implies x != 0
        if (from is SmtExpr.Comparison && to is SmtExpr.Not && to.inner is SmtExpr.Comparison) {
            val neg = to.inner
            if (from.op == ">" && neg.op == "=" && from.left == neg.left) {
                // x > c implies x != c, and x > c implies x != anything <= c
                val fromRight = (from.right as? SmtExpr.Num)?.value
                val negRight = (neg.right as? SmtExpr.Num)?.value
                if (fromRight != null && negRight != null && fromRight >= negRight) return true
            }
            if (from.op == "<" && neg.op == "=" && from.left == neg.left) {
                val fromRight = (from.right as? SmtExpr.Num)?.value
                val negRight = (neg.right as? SmtExpr.Num)?.value
                if (fromRight != null && negRight != null && fromRight <= negRight) return true
            }
        }

        // from: (> x 0) => to: (>= x 0) or (>= x 1)
        if (from is SmtExpr.Comparison && to is SmtExpr.Comparison && from.left == to.left) {
            val fromVal = (from.right as? SmtExpr.Num)?.value
            val toVal = (to.right as? SmtExpr.Num)?.value
            if (fromVal != null && toVal != null) {
                // > c implies >= c, >= c+1, > c-1, etc.
                if (from.op == ">" && to.op == ">=" && toVal <= fromVal) return true
                if (from.op == ">" && to.op == ">" && toVal <= fromVal) return true
                if (from.op == ">=" && to.op == ">=" && toVal <= fromVal) return true
                if (from.op == ">=" && to.op == ">" && toVal < fromVal) return true
                if (from.op == "<" && to.op == "<=" && toVal >= fromVal) return true
                if (from.op == "<" && to.op == "<" && toVal >= fromVal) return true
                if (from.op == "<=" && to.op == "<=" && toVal >= fromVal) return true
                if (from.op == "<=" && to.op == "<" && toVal > fromVal) return true
            }
        }

        return false
    }

    // Simple SMT expression tree for built-in solver
    sealed class SmtExpr {
        data class Num(val value: Long) : SmtExpr()
        data class Var(val name: String) : SmtExpr()
        data class Bool(val value: kotlin.Boolean) : SmtExpr()
        data class Comparison(val op: String, val left: SmtExpr, val right: SmtExpr) : SmtExpr()
        data class Not(val inner: SmtExpr) : SmtExpr()
        data class And(val args: List<SmtExpr>) : SmtExpr()
        data class Or(val args: List<SmtExpr>) : SmtExpr()
        data class Arith(val op: String, val left: SmtExpr, val right: SmtExpr) : SmtExpr()
    }

    internal fun parseSmtExpr(s: String): SmtExpr? {
        val trimmed = s.trim()
        // Number literal
        trimmed.toLongOrNull()?.let { return SmtExpr.Num(it) }
        // Boolean
        if (trimmed == "true") return SmtExpr.Bool(true)
        if (trimmed == "false") return SmtExpr.Bool(false)
        // Variable (no parens, not a number)
        if (!trimmed.startsWith("(")) return SmtExpr.Var(trimmed)

        // S-expression
        val inner = trimmed.removePrefix("(").removeSuffix(")").trim()
        val tokens = tokenizeSExpr(inner)
        if (tokens.isEmpty()) return null

        val op = tokens[0]
        val args = tokens.drop(1).mapNotNull { parseSmtExpr(it) }

        return when (op) {
            "not" -> if (args.size == 1) SmtExpr.Not(args[0]) else null
            "and" -> SmtExpr.And(args)
            "or" -> SmtExpr.Or(args)
            ">", "<", ">=", "<=", "=" -> {
                if (args.size == 2) SmtExpr.Comparison(op, args[0], args[1]) else null
            }
            "+", "-", "*", "div" -> {
                if (args.size == 2) SmtExpr.Arith(op, args[0], args[1])
                else if (args.size == 1 && op == "-") {
                    // Unary minus: (- 5) = -5
                    val inner2 = args[0]
                    if (inner2 is SmtExpr.Num) SmtExpr.Num(-inner2.value) else null
                } else null
            }
            else -> null
        }
    }

    private fun tokenizeSExpr(s: String): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        while (i < s.length) {
            when {
                s[i].isWhitespace() -> i++
                s[i] == '(' -> {
                    var depth = 1
                    val start = i
                    i++
                    while (i < s.length && depth > 0) {
                        if (s[i] == '(') depth++
                        if (s[i] == ')') depth--
                        i++
                    }
                    tokens.add(s.substring(start, i))
                }
                else -> {
                    val start = i
                    while (i < s.length && !s[i].isWhitespace() && s[i] != '(' && s[i] != ')') i++
                    tokens.add(s.substring(start, i))
                }
            }
        }
        return tokens
    }
}

class Z3SmtSolver : SmtSolver {
    private val encoder = SmtEncoder()
    private val fallback = SimpleSmtSolver()

    private val z3Available: Boolean by lazy {
        try {
            val process = ProcessBuilder("z3", "--version")
                .redirectErrorStream(true)
                .start()
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun verify(smtProgram: String): SmtResult {
        if (!z3Available) return fallback.verify(smtProgram)

        return try {
            val process = ProcessBuilder("z3", "-in", "-smt2")
                .redirectErrorStream(true)
                .start()

            process.outputStream.bufferedWriter().use { it.write(smtProgram) }
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()

            when {
                output.contains("unsat") -> SmtResult.UNSAT
                output.contains("sat") -> SmtResult.SAT
                else -> SmtResult.UNKNOWN
            }
        } catch (_: Exception) {
            fallback.verify(smtProgram)
        }
    }

    override suspend fun verifyContracts(fn: FnDef): VerificationResult {
        val contract = fn.contract
            ?: return VerificationResult(SmtResult.SAT, emptyList(), emptyList())

        val proved = mutableListOf<String>()
        val unproved = mutableListOf<String>()

        for ((index, clause) in contract.ensures.withIndex()) {
            val clauseLabel = "ensures[$index]"
            val singleFn = fn.copy(
                contract = ContractNode(requires = contract.requires, ensures = listOf(clause))
            )
            val smtProgram = encoder.encodeContract(singleFn)
            val result = verify(smtProgram)

            when (result) {
                SmtResult.UNSAT -> proved.add(clauseLabel)
                else -> unproved.add(clauseLabel)
            }
        }

        val overallResult = when {
            unproved.isEmpty() && proved.isNotEmpty() -> SmtResult.UNSAT
            unproved.isNotEmpty() -> SmtResult.SAT
            else -> SmtResult.SAT
        }

        return VerificationResult(
            result = overallResult,
            provedClauses = proved,
            unprovedClauses = unproved,
            smtOutput = encoder.encodeContract(fn)
        )
    }

    override suspend fun verifyChaining(fnA: FnDef, fnB: FnDef): VerificationResult {
        val smtProgram = encoder.encodeChaining(fnA, fnB)
        val result = verify(smtProgram)

        val requiresB = fnB.contract?.requires ?: emptyList()
        val proved = mutableListOf<String>()
        val unproved = mutableListOf<String>()

        if (result == SmtResult.UNSAT) {
            requiresB.forEachIndexed { i, _ -> proved.add("requires[$i]") }
        } else {
            requiresB.forEachIndexed { i, _ -> unproved.add("requires[$i]") }
        }

        return VerificationResult(
            result = result,
            provedClauses = proved,
            unprovedClauses = unproved,
            smtOutput = smtProgram
        )
    }
}
