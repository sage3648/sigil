package sigil.bench

import org.junit.jupiter.api.Test
import sigil.api.SigilCompiler
import sigil.codegen.jvm.JvmCodegen
import sigil.hash.Hasher
import sigil.parser.parse

class CompilationBench {

    private val runner = BenchmarkRunner(warmupIterations = 50, measuredIterations = 500)

    @Test
    fun `benchmark compilation pipeline`() {
        val results = mutableListOf<BenchResult>()

        val simpleSource = "fn add(a: Int, b: Int) -> Int { a + b }"
        val complexSource = "fn compute(x: Int, y: Int) -> Int {\nlet a = x + y\nlet b = a * x\nif b > 0 then b else a\n}"

        // Parse stage
        val parseSimple = runner.bench("parse simple") { parse(simpleSource) }
        results += parseSimple

        val parseComplex = runner.bench("parse complex") { parse(complexSource) }
        results += parseComplex

        // Full pipeline (parse + typecheck + hash + codegen + link)
        val pipelineSimple = runner.bench("pipeline simple") {
            SigilCompiler().compileSource(simpleSource)
        }
        results += pipelineSimple

        val pipelineComplex = runner.bench("pipeline complex") {
            SigilCompiler().compileSource(complexSource)
        }
        results += pipelineComplex

        // Hash stage only (using pre-parsed AST)
        val parsedSimple = parse(simpleSource)
        val fnSimple = parsedSimple[0] as sigil.ast.FnDef
        val hashSimple = runner.bench("hash simple") { Hasher.hashFnDef(fnSimple) }
        results += hashSimple

        val parsedComplex = parse(complexSource)
        val fnComplex = parsedComplex[0] as sigil.ast.FnDef
        val hashComplex = runner.bench("hash complex") { Hasher.hashFnDef(fnComplex) }
        results += hashComplex

        // Codegen stage only (using pre-hashed AST)
        val simpleHash = Hasher.hashFnDef(fnSimple)
        val codegenSimple = runner.bench("codegen simple") {
            JvmCodegen().compileFnDef(fnSimple, simpleHash)
        }
        results += codegenSimple

        val complexHash = Hasher.hashFnDef(fnComplex)
        val codegenComplex = runner.bench("codegen complex") {
            JvmCodegen().compileFnDef(fnComplex, complexHash)
        }
        results += codegenComplex

        runner.report(results)

        // Throughput summary
        println("--- Throughput ---")
        println("Simple fn pipeline: %,.0f functions/sec".format(pipelineSimple.opsPerSec))
        println("Complex fn pipeline: %,.0f functions/sec".format(pipelineComplex.opsPerSec))
        println()
    }
}
