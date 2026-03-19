package sigil.bench

import kotlin.math.sqrt

data class BenchResult(
    val name: String,
    val iterations: Int,
    val totalNanos: Long,
    val meanNanos: Long,
    val stdDevNanos: Long,
    val opsPerSec: Double
)

class BenchmarkRunner(
    val warmupIterations: Int = 100,
    val measuredIterations: Int = 1000
) {
    fun <T> bench(name: String, block: () -> T): BenchResult {
        // Warmup
        repeat(warmupIterations) { block() }

        // Measure
        val times = LongArray(measuredIterations)
        for (i in 0 until measuredIterations) {
            val start = System.nanoTime()
            block()
            times[i] = System.nanoTime() - start
        }

        val totalNanos = times.sum()
        val meanNanos = totalNanos / measuredIterations
        val variance = times.map { (it - meanNanos).toDouble() * (it - meanNanos).toDouble() }.average()
        val stdDevNanos = sqrt(variance).toLong()
        val opsPerSec = if (meanNanos > 0) 1_000_000_000.0 / meanNanos else Double.MAX_VALUE

        return BenchResult(name, measuredIterations, totalNanos, meanNanos, stdDevNanos, opsPerSec)
    }

    fun report(results: List<BenchResult>) {
        val maxName = results.maxOf { it.name.length }
        println()
        println("%-${maxName}s  %12s  %12s  %12s  %15s".format("Benchmark", "Mean (ns)", "StdDev (ns)", "Iterations", "Ops/sec"))
        println("-".repeat(maxName + 56))
        for (r in results) {
            println(
                "%-${maxName}s  %,12d  %,12d  %,12d  %,15.0f".format(
                    r.name, r.meanNanos, r.stdDevNanos, r.iterations, r.opsPerSec
                )
            )
        }
        println()
    }

    fun compare(name: String, sigilResult: BenchResult, kotlinResult: BenchResult): String {
        val ratio = sigilResult.meanNanos.toDouble() / kotlinResult.meanNanos.toDouble()
        val status = when {
            ratio <= 1.5 -> "OK"
            ratio <= 3.0 -> "ACCEPTABLE"
            ratio <= 10.0 -> "SLOW"
            else -> "VERY SLOW"
        }
        return "[$status] $name: Sigil=${sigilResult.meanNanos}ns vs Kotlin=${kotlinResult.meanNanos}ns (ratio=%.2fx)".format(ratio)
    }
}
