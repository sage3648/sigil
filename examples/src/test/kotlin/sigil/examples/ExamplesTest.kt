package sigil.examples

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import sigil.codegen.jvm.ContractViolation
import sigil.interop.SigilModule
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExamplesTest {

    // --- Math module tests ---

    private val mathModule by lazy { SigilModule.compile(SigilPrograms.MATH) }
    private val validatorModule by lazy { SigilModule.compile(SigilPrograms.VALIDATORS) }
    private val scoringModule by lazy { SigilModule.compile(SigilPrograms.SCORING) }

    @Test
    fun `abs of negative returns positive`() {
        assertEquals(42L, mathModule.call<Long>("abs", -42L))
    }

    @Test
    fun `abs of positive returns same`() {
        assertEquals(7L, mathModule.call<Long>("abs", 7L))
    }

    @Test
    fun `abs of zero returns zero`() {
        assertEquals(0L, mathModule.call<Long>("abs", 0L))
    }

    @Test
    fun `clamp below range returns lo`() {
        assertEquals(0L, mathModule.call<Long>("clamp", -5L, 0L, 100L))
    }

    @Test
    fun `clamp above range returns hi`() {
        assertEquals(100L, mathModule.call<Long>("clamp", 150L, 0L, 100L))
    }

    @Test
    fun `clamp within range returns value`() {
        assertEquals(50L, mathModule.call<Long>("clamp", 50L, 0L, 100L))
    }

    @Test
    fun `clamp at boundaries`() {
        assertEquals(0L, mathModule.call<Long>("clamp", 0L, 0L, 100L))
        assertEquals(100L, mathModule.call<Long>("clamp", 100L, 0L, 100L))
    }

    @Test
    fun `max returns larger value`() {
        assertEquals(12L, mathModule.call<Long>("max", 7L, 12L))
        assertEquals(12L, mathModule.call<Long>("max", 12L, 7L))
    }

    @Test
    fun `max with equal values`() {
        assertEquals(5L, mathModule.call<Long>("max", 5L, 5L))
    }

    @Test
    fun `min returns smaller value`() {
        assertEquals(7L, mathModule.call<Long>("min", 7L, 12L))
        assertEquals(7L, mathModule.call<Long>("min", 12L, 7L))
    }

    @Test
    fun `min with equal values`() {
        assertEquals(5L, mathModule.call<Long>("min", 5L, 5L))
    }

    // --- Validator module tests ---

    @Test
    fun `is_positive returns true for positive`() {
        assertTrue(validatorModule.call<Boolean>("is_positive", 5L))
        assertTrue(validatorModule.call<Boolean>("is_positive", 1L))
    }

    @Test
    fun `is_positive returns false for zero and negative`() {
        assertFalse(validatorModule.call<Boolean>("is_positive", 0L))
        assertFalse(validatorModule.call<Boolean>("is_positive", -1L))
    }

    @Test
    fun `is_non_negative returns true for zero and positive`() {
        assertTrue(validatorModule.call<Boolean>("is_non_negative", 0L))
        assertTrue(validatorModule.call<Boolean>("is_non_negative", 5L))
    }

    @Test
    fun `is_non_negative returns false for negative`() {
        assertFalse(validatorModule.call<Boolean>("is_non_negative", -1L))
    }

    @Test
    fun `is_in_range within bounds`() {
        assertTrue(validatorModule.call<Boolean>("is_in_range", 50L, 0L, 100L))
        assertTrue(validatorModule.call<Boolean>("is_in_range", 0L, 0L, 100L))
        assertTrue(validatorModule.call<Boolean>("is_in_range", 100L, 0L, 100L))
    }

    @Test
    fun `is_in_range out of bounds`() {
        assertFalse(validatorModule.call<Boolean>("is_in_range", -1L, 0L, 100L))
        assertFalse(validatorModule.call<Boolean>("is_in_range", 101L, 0L, 100L))
    }

    @Test
    fun `safe_divide with valid divisor`() {
        assertEquals(5L, validatorModule.call<Long>("safe_divide", 10L, 2L))
        assertEquals(3L, validatorModule.call<Long>("safe_divide", 10L, 3L))
    }

    @Test
    fun `safe_divide with zero divisor throws contract violation`() {
        val ex = assertThrows<java.lang.reflect.InvocationTargetException> {
            validatorModule.call<Long>("safe_divide", 10L, 0L)
        }
        assertTrue(ex.cause is ContractViolation)
    }

    // --- Scoring module tests ---

    @Test
    fun `weighted_score basic computation`() {
        // 10 * 3 + 5 = 35
        assertEquals(35L, scoringModule.call<Long>("weighted_score", 10L, 5L, 3L))
    }

    @Test
    fun `weighted_score clamps negative to zero`() {
        // -10 * 1 + 5 = -5, clamped to 0
        assertEquals(0L, scoringModule.call<Long>("weighted_score", -10L, 5L, 1L))
    }

    @Test
    fun `weighted_score contract requires positive multiplier`() {
        val ex = assertThrows<java.lang.reflect.InvocationTargetException> {
            scoringModule.call<Long>("weighted_score", 10L, 5L, 0L)
        }
        assertTrue(ex.cause is ContractViolation)
    }

    @Test
    fun `normalize_score basic computation`() {
        // 75 * 100 / 200 = 37
        assertEquals(37L, scoringModule.call<Long>("normalize_score", 75L, 200L))
    }

    @Test
    fun `normalize_score clamps to 0-100`() {
        assertEquals(0L, scoringModule.call<Long>("normalize_score", -10L, 100L))
        assertEquals(100L, scoringModule.call<Long>("normalize_score", 200L, 100L))
    }

    @Test
    fun `normalize_score contract requires positive max`() {
        val ex = assertThrows<java.lang.reflect.InvocationTargetException> {
            scoringModule.call<Long>("normalize_score", 50L, 0L)
        }
        assertTrue(ex.cause is ContractViolation)
    }

    @Test
    fun `rank_tier assigns correct tiers`() {
        assertEquals(1L, scoringModule.call<Long>("rank_tier", 95L))
        assertEquals(1L, scoringModule.call<Long>("rank_tier", 90L))
        assertEquals(2L, scoringModule.call<Long>("rank_tier", 75L))
        assertEquals(2L, scoringModule.call<Long>("rank_tier", 70L))
        assertEquals(3L, scoringModule.call<Long>("rank_tier", 55L))
        assertEquals(3L, scoringModule.call<Long>("rank_tier", 50L))
        assertEquals(4L, scoringModule.call<Long>("rank_tier", 49L))
        assertEquals(4L, scoringModule.call<Long>("rank_tier", 0L))
    }

    // --- Sigil vs Kotlin comparison ---

    @Test
    fun `sigil abs matches kotlin abs`() {
        val kotlinAbs = { x: Long -> if (x < 0) -x else x }
        for (x in listOf(-100L, -1L, 0L, 1L, 100L)) {
            assertEquals(kotlinAbs(x), mathModule.call<Long>("abs", x), "abs($x)")
        }
    }

    @Test
    fun `sigil clamp matches kotlin clamp`() {
        val kotlinClamp = { x: Long, lo: Long, hi: Long ->
            if (x < lo) lo else if (x > hi) hi else x
        }
        for (x in listOf(-10L, 0L, 50L, 100L, 200L)) {
            assertEquals(
                kotlinClamp(x, 0L, 100L),
                mathModule.call<Long>("clamp", x, 0L, 100L),
                "clamp($x, 0, 100)"
            )
        }
    }

    @Test
    fun `sigil weighted_score matches kotlin implementation`() {
        val kotlinWeighted = { base: Long, bonus: Long, mult: Long ->
            val raw = base * mult + bonus
            if (raw < 0) 0L else raw
        }
        assertEquals(
            kotlinWeighted(10L, 5L, 3L),
            scoringModule.call<Long>("weighted_score", 10L, 5L, 3L)
        )
        assertEquals(
            kotlinWeighted(1L, 1L, 1L),
            scoringModule.call<Long>("weighted_score", 1L, 1L, 1L)
        )
    }

    // --- Module introspection ---

    @Test
    fun `math module lists all functions`() {
        val fns = mathModule.listFunctions().map { it.name }.toSet()
        assertEquals(setOf("abs", "clamp", "max", "min"), fns)
    }

    @Test
    fun `scoring module functions have contracts`() {
        val ws = scoringModule.getFunction("weighted_score")!!
        assertTrue(ws.hasContracts)

        val rt = scoringModule.getFunction("rank_tier")!!
        assertFalse(rt.hasContracts)
    }

    @Test
    fun `content-addressed hashing is deterministic`() {
        val module1 = SigilModule.compile(SigilPrograms.MATH)
        val module2 = SigilModule.compile(SigilPrograms.MATH)
        val hashes1 = module1.listFunctions().sortedBy { it.name }.map { it.hash }
        val hashes2 = module2.listFunctions().sortedBy { it.name }.map { it.hash }
        assertEquals(hashes1, hashes2)
    }

    // --- Timing comparison (informational) ---

    @Test
    fun `timing comparison sigil vs kotlin`() {
        val iterations = 100_000

        // Kotlin implementation
        val kotlinClamp = { x: Long, lo: Long, hi: Long ->
            if (x < lo) lo else if (x > hi) hi else x
        }

        // Warm up
        repeat(1000) {
            mathModule.call<Long>("clamp", 50L, 0L, 100L)
            kotlinClamp(50L, 0L, 100L)
        }

        val sigilStart = System.nanoTime()
        repeat(iterations) {
            mathModule.call<Long>("clamp", 50L, 0L, 100L)
        }
        val sigilTime = System.nanoTime() - sigilStart

        val kotlinStart = System.nanoTime()
        repeat(iterations) {
            kotlinClamp(50L, 0L, 100L)
        }
        val kotlinTime = System.nanoTime() - kotlinStart

        println("Timing comparison ($iterations iterations of clamp):")
        println("  Sigil:  ${sigilTime / 1_000_000}ms (${sigilTime / iterations}ns/call)")
        println("  Kotlin: ${kotlinTime / 1_000_000}ms (${kotlinTime / iterations}ns/call)")
        println("  Ratio:  %.2fx".format(sigilTime.toDouble() / kotlinTime))
    }
}
