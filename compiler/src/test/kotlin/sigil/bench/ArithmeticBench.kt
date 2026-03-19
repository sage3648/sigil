package sigil.bench

import org.junit.jupiter.api.Test
import sigil.api.SigilCompiler
import java.lang.reflect.Method

class ArithmeticBench {

    private val runner = BenchmarkRunner(warmupIterations = 200, measuredIterations = 2000)

    private fun compileSigilFn(source: String): Pair<Method, String> {
        val compiler = SigilCompiler()
        val result = compiler.compileSource(source)
        val cr = result.functions.first()
        val clazz = result.classLoader.loadClass(cr.className)
        val fnName = source.substringAfter("fn ").substringBefore("(")
        val method = clazz.methods.first { it.name == fnName }
        return method to fnName
    }

    @Test
    fun `benchmark simple arithmetic`() {
        val results = mutableListOf<BenchResult>()
        val comparisons = mutableListOf<String>()

        // square: x * x
        val (squareMethod) = compileSigilFn("fn square(x: Int) -> Int { x * x }")
        val sigilSquare = runner.bench("sigil square") { squareMethod.invoke(null, 7L) }
        val kotlinSquare = runner.bench("kotlin square") { 7L * 7L }
        results += sigilSquare
        results += kotlinSquare
        comparisons += runner.compare("square", sigilSquare, kotlinSquare)

        // add: a + b
        val (addMethod) = compileSigilFn("fn add(a: Int, b: Int) -> Int { a + b }")
        val sigilAdd = runner.bench("sigil add") { addMethod.invoke(null, 3L, 4L) }
        val kotlinAdd = runner.bench("kotlin add") { 3L + 4L }
        results += sigilAdd
        results += kotlinAdd
        comparisons += runner.compare("add", sigilAdd, kotlinAdd)

        runner.report(results)
        println("--- Comparisons ---")
        comparisons.forEach { println(it) }
        println()
    }

    @Test
    fun `benchmark complex expressions`() {
        val results = mutableListOf<BenchResult>()
        val comparisons = mutableListOf<String>()

        // complex: (a + b) * c - (a * b)
        val source = "fn complex(a: Int, b: Int, c: Int) -> Int { (a + b) * c - a * b }"
        val (complexMethod) = compileSigilFn(source)
        val sigilComplex = runner.bench("sigil complex") { complexMethod.invoke(null, 3L, 4L, 5L) }
        val kotlinComplex = runner.bench("kotlin complex") { (3L + 4L) * 5L - 3L * 4L }
        results += sigilComplex
        results += kotlinComplex
        comparisons += runner.compare("complex", sigilComplex, kotlinComplex)

        runner.report(results)
        println("--- Comparisons ---")
        comparisons.forEach { println(it) }
        println()
    }

    @Test
    fun `benchmark conditional logic`() {
        val results = mutableListOf<BenchResult>()
        val comparisons = mutableListOf<String>()

        // max: if a > b then a else b
        val source = "fn max(a: Int, b: Int) -> Int { if a > b then a else b }"
        val (maxMethod) = compileSigilFn(source)
        val sigilMax = runner.bench("sigil max") { maxMethod.invoke(null, 5L, 3L) }
        val kotlinMax = runner.bench("kotlin max") { if (5L > 3L) 5L else 3L }
        results += sigilMax
        results += kotlinMax
        comparisons += runner.compare("max", sigilMax, kotlinMax)

        runner.report(results)
        println("--- Comparisons ---")
        comparisons.forEach { println(it) }
        println()
    }

    @Test
    fun `benchmark let bindings`() {
        val results = mutableListOf<BenchResult>()
        val comparisons = mutableListOf<String>()

        // chained let bindings (newline-separated)
        val source = "fn compute(x: Int) -> Int {\nlet a = x + 1\nlet b = a * 2\nlet c = b - 3\nc\n}"
        val (computeMethod) = compileSigilFn(source)
        val sigilCompute = runner.bench("sigil let-chain") { computeMethod.invoke(null, 10L) }
        val kotlinCompute = runner.bench("kotlin let-chain") {
            val a = 10L + 1L
            val b = a * 2L
            b - 3L
        }
        results += sigilCompute
        results += kotlinCompute
        comparisons += runner.compare("let-chain", sigilCompute, kotlinCompute)

        runner.report(results)
        println("--- Comparisons ---")
        comparisons.forEach { println(it) }
        println()
    }
}
