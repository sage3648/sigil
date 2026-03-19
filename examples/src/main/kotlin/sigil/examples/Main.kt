package sigil.examples

import sigil.interop.SigilModule

fun main() {
    val mathModule = SigilModule.compile(SigilPrograms.MATH)
    val validatorModule = SigilModule.compile(SigilPrograms.VALIDATORS)
    val scoringModule = SigilModule.compile(SigilPrograms.SCORING)

    println("=== Sigil Dogfooding Demo ===")
    println()

    // Math functions
    println("--- Math ---")
    println("abs(-42) = ${mathModule.call<Long>("abs", -42L)}")
    println("abs(7) = ${mathModule.call<Long>("abs", 7L)}")
    println("clamp(150, 0, 100) = ${mathModule.call<Long>("clamp", 150L, 0L, 100L)}")
    println("clamp(-5, 0, 100) = ${mathModule.call<Long>("clamp", -5L, 0L, 100L)}")
    println("clamp(50, 0, 100) = ${mathModule.call<Long>("clamp", 50L, 0L, 100L)}")
    println("max(7, 12) = ${mathModule.call<Long>("max", 7L, 12L)}")
    println("min(7, 12) = ${mathModule.call<Long>("min", 7L, 12L)}")
    println()

    // Validators
    println("--- Validators ---")
    println("is_positive(5) = ${validatorModule.call<Boolean>("is_positive", 5L)}")
    println("is_positive(-3) = ${validatorModule.call<Boolean>("is_positive", -3L)}")
    println("is_in_range(50, 0, 100) = ${validatorModule.call<Boolean>("is_in_range", 50L, 0L, 100L)}")
    println("safe_divide(10, 3) = ${validatorModule.call<Long>("safe_divide", 10L, 3L)}")
    println()

    // Scoring
    println("--- Scoring ---")
    println("weighted_score(10, 5, 3) = ${scoringModule.call<Long>("weighted_score", 10L, 5L, 3L)}")
    println("normalize_score(75, 200) = ${scoringModule.call<Long>("normalize_score", 75L, 200L)}")
    println("rank_tier(95) = ${scoringModule.call<Long>("rank_tier", 95L)}")
    println("rank_tier(42) = ${scoringModule.call<Long>("rank_tier", 42L)}")
    println()

    // Contract enforcement demo
    println("--- Contract Enforcement ---")
    try {
        validatorModule.call<Long>("safe_divide", 10L, 0L)
        println("safe_divide(10, 0) = (should not reach here)")
    } catch (e: Exception) {
        val cause = if (e is java.lang.reflect.InvocationTargetException) e.cause else e
        println("safe_divide(10, 0) -> Contract violated: ${cause?.message}")
    }

    try {
        scoringModule.call<Long>("weighted_score", 10L, 5L, 0L)
        println("weighted_score(10, 5, 0) = (should not reach here)")
    } catch (e: Exception) {
        val cause = if (e is java.lang.reflect.InvocationTargetException) e.cause else e
        println("weighted_score(10, 5, 0) -> Contract violated: ${cause?.message}")
    }

    println()
    println("=== Module Introspection ===")
    for (fn in mathModule.listFunctions()) {
        println("  ${fn.name}: ${fn.paramTypes.size} params, hash=${fn.hash.take(12)}...")
    }
}
