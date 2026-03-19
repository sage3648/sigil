package sigil.bench

import org.junit.jupiter.api.Test
import sigil.hash.Blake3

class HashingBench {

    private val runner = BenchmarkRunner(warmupIterations = 200, measuredIterations = 2000)

    @Test
    fun `benchmark blake3 hashing`() {
        val results = mutableListOf<BenchResult>()

        val small = ByteArray(100) { it.toByte() }   // 100 B
        val medium = ByteArray(1024) { it.toByte() }  // 1 KB
        val large = ByteArray(10240) { it.toByte() }  // 10 KB

        val hashSmall = runner.bench("blake3 100B") { Blake3.hash(small) }
        results += hashSmall

        val hashMedium = runner.bench("blake3 1KB") { Blake3.hash(medium) }
        results += hashMedium

        val hashLarge = runner.bench("blake3 10KB") { Blake3.hash(large) }
        results += hashLarge

        runner.report(results)

        // Throughput in MB/s
        println("--- Throughput ---")
        for ((label, size, result) in listOf(
            Triple("100B", 100, hashSmall),
            Triple("1KB", 1024, hashMedium),
            Triple("10KB", 10240, hashLarge)
        )) {
            val bytesPerSec = size.toDouble() * result.opsPerSec
            val mbPerSec = bytesPerSec / (1024.0 * 1024.0)
            println("$label: %.1f MB/s".format(mbPerSec))
        }
        println()
    }
}
