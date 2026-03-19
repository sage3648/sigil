package sigil.bench

import org.junit.jupiter.api.Test
import sigil.api.SigilCompiler
import java.lang.reflect.Method

class ContractOverheadBench {

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
    fun `benchmark contract overhead`() {
        val results = mutableListOf<BenchResult>()

        // Identity without contract
        val (identityMethod) = compileSigilFn("fn identity(x: Int) -> Int { x }")
        val noContract = runner.bench("identity (no contract)") {
            identityMethod.invoke(null, 42L)
        }
        results += noContract

        // Identity with requires contract
        val requiresSource = "fn identityReq(x: Int) -> Int {\nrequires x > 0\nx\n}"
        val (reqMethod) = compileSigilFn(requiresSource)
        val withRequires = runner.bench("identity (requires)") {
            reqMethod.invoke(null, 42L)
        }
        results += withRequires

        // Identity with ensures contract
        val ensuresSource = "fn identityEns(x: Int) -> Int {\nensures result > 0\nx\n}"
        val (ensMethod) = compileSigilFn(ensuresSource)
        val withEnsures = runner.bench("identity (ensures)") {
            ensMethod.invoke(null, 42L)
        }
        results += withEnsures

        // Identity with both requires and ensures
        val bothSource = "fn identityBoth(x: Int) -> Int {\nrequires x > 0\nensures result > 0\nx\n}"
        val (bothMethod) = compileSigilFn(bothSource)
        val withBoth = runner.bench("identity (requires+ensures)") {
            bothMethod.invoke(null, 42L)
        }
        results += withBoth

        runner.report(results)

        // Report overhead
        val baseNanos = noContract.meanNanos.toDouble()
        println("--- Contract Overhead ---")
        println("requires:         +%,d ns (%.1fx)".format(
            withRequires.meanNanos - noContract.meanNanos,
            withRequires.meanNanos / baseNanos
        ))
        println("ensures:          +%,d ns (%.1fx)".format(
            withEnsures.meanNanos - noContract.meanNanos,
            withEnsures.meanNanos / baseNanos
        ))
        println("requires+ensures: +%,d ns (%.1fx)".format(
            withBoth.meanNanos - noContract.meanNanos,
            withBoth.meanNanos / baseNanos
        ))
        println()
    }
}
