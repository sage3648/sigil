package sigil.interop

import sigil.api.CompilationResult
import sigil.api.SigilCompiler
import sigil.ast.FnDef
import sigil.ast.Hash
import java.lang.reflect.Method

data class CompiledFunction(
    val name: String,
    val hash: Hash,
    val className: String,
    val method: Method,
    val fnDef: FnDef
)

data class SigilFunction(
    val name: String,
    val hash: Hash,
    val paramTypes: List<String>,
    val returnType: String,
    val effects: Set<String>,
    val hasContracts: Boolean
)

data class FunctionInfo(
    val name: String,
    val hash: Hash,
    val paramTypes: List<String>,
    val returnType: String,
    val effects: Set<String>
)

class SigilModule(
    private val classLoader: ClassLoader,
    private val functions: Map<String, CompiledFunction>
) {

    @Suppress("UNCHECKED_CAST")
    fun <R> call(fnName: String, vararg args: Any?): R {
        val compiled = functions[fnName]
            ?: throw IllegalArgumentException("Function '$fnName' not found. Available: ${functions.keys}")

        val result = compiled.method.invoke(null, *args)
        return result as R
    }

    fun getFunction(fnName: String): SigilFunction? {
        val compiled = functions[fnName] ?: return null
        val fn = compiled.fnDef
        return SigilFunction(
            name = compiled.name,
            hash = compiled.hash,
            paramTypes = fn.params.map { it.type.defHash },
            returnType = fn.returnType.defHash,
            effects = fn.effects,
            hasContracts = fn.contract != null
        )
    }

    fun listFunctions(): List<FunctionInfo> = functions.values.map { compiled ->
        val fn = compiled.fnDef
        FunctionInfo(
            name = compiled.name,
            hash = compiled.hash,
            paramTypes = fn.params.map { it.type.defHash },
            returnType = fn.returnType.defHash,
            effects = fn.effects
        )
    }

    companion object {
        fun compile(source: String): SigilModule {
            val compiler = SigilCompiler()
            val pipeline = compiler.compileSource(source)

            val fnDefs = sigil.parser.parse(source).filterIsInstance<FnDef>()

            val fnMap = mutableMapOf<String, CompiledFunction>()
            for ((i, result) in pipeline.functions.withIndex()) {
                val fnDef = fnDefs[i]
                val clazz = pipeline.classLoader.loadClass(result.className)
                val paramTypes = fnDef.params.map { KotlinTypeMapper.sigilToJvmClass(it.type) }.toTypedArray()
                val method = clazz.getMethod(fnDef.name, *paramTypes)

                fnMap[fnDef.name] = CompiledFunction(
                    name = fnDef.name,
                    hash = result.hash,
                    className = result.className,
                    method = method,
                    fnDef = fnDef
                )
            }

            return SigilModule(pipeline.classLoader, fnMap)
        }

        fun compile(fns: List<FnDef>): SigilModule {
            val compiler = SigilCompiler()
            val results = mutableListOf<CompilationResult>()
            val classes = mutableMapOf<String, ByteArray>()

            for (fn in fns) {
                val result = compiler.compileFn(fn)
                results.add(result)
                classes[result.className] = result.bytecode
            }

            val linker = sigil.codegen.jvm.JvmLinker()
            val classLoader = linker.loadClasses(classes)

            val fnMap = mutableMapOf<String, CompiledFunction>()
            for ((i, result) in results.withIndex()) {
                val fnDef = fns[i]
                val clazz = classLoader.loadClass(result.className)
                val paramTypes = fnDef.params.map { KotlinTypeMapper.sigilToJvmClass(it.type) }.toTypedArray()
                val method = clazz.getMethod(fnDef.name, *paramTypes)

                fnMap[fnDef.name] = CompiledFunction(
                    name = fnDef.name,
                    hash = result.hash,
                    className = result.className,
                    method = method,
                    fnDef = fnDef
                )
            }

            return SigilModule(classLoader, fnMap)
        }
    }
}
