package sigil.api

import sigil.ast.*
import sigil.codegen.jvm.JvmCodegen
import sigil.codegen.jvm.JvmLinker
import sigil.contracts.ContractVerifier
import sigil.effects.EffectChecker
import sigil.hash.Hasher
import sigil.parser.parse
import sigil.types.TypeChecker

data class CompilationResult(
    val hash: Hash,
    val className: String,
    val bytecode: ByteArray,
    val verificationTier: Int = 1
)

data class PipelineResult(
    val functions: List<CompilationResult>,
    val classLoader: ClassLoader
)

class SigilCompiler {
    private val typeChecker = TypeChecker()
    private val effectChecker = EffectChecker()
    private val contractVerifier = ContractVerifier(typeChecker)
    private val codegen = JvmCodegen()
    private val linker = JvmLinker()

    init {
        typeChecker.registerPrimitives()
    }

    /**
     * Compile a single FnDef through the full pipeline:
     * type check -> effect check -> contract verify -> hash -> codegen
     */
    fun compileFn(fn: FnDef): CompilationResult {
        // 1. Type check
        typeChecker.checkFnDef(fn)

        // 2. Effect check
        effectChecker.checkFnDef(fn)

        // 3. Contract verification
        if (fn.contract != null) {
            contractVerifier.verifyContracts(fn)
        }

        // 4. Compute content hash
        val hash = Hasher.hashFnDef(fn)

        // 5. Compile to JVM bytecode
        val bytecode = codegen.compileFnDef(fn, hash)
        val className = "Sigil_${hash.take(8)}"

        return CompilationResult(hash, className, bytecode)
    }

    /**
     * Compile from source text through the full pipeline.
     */
    fun compileSource(source: String): PipelineResult {
        val parsed = parse(source)
        val results = mutableListOf<CompilationResult>()
        val classes = mutableMapOf<String, ByteArray>()

        for (item in parsed) {
            when (item) {
                is FnDef -> {
                    val result = compileFn(item)
                    results.add(result)
                    classes[result.className] = result.bytecode
                }
            }
        }

        val classLoader = linker.loadClasses(classes)
        return PipelineResult(results, classLoader)
    }

    /**
     * Register a type definition.
     */
    fun registerType(td: TypeDef) {
        typeChecker.registerTypeDef(td)
    }

    /**
     * Register an effect definition.
     */
    fun registerEffect(ed: EffectDef) {
        effectChecker.registerEffect(ed)
    }
}
