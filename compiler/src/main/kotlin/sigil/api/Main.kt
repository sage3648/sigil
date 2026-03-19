package sigil.api

fun main(args: Array<String>) {
    println("Sigil Compiler v0.1.0")
    println("Usage: provide Sigil source via stdin or as a file argument")

    val source = if (args.isNotEmpty()) {
        java.io.File(args[0]).readText()
    } else if (System.`in`.available() > 0) {
        System.`in`.bufferedReader().readText()
    } else {
        println()
        println("Example: echo 'fn add(a: Int, b: Int) -> Int { a + b }' | sigil-compiler")
        println()
        println("Pipeline: Parse -> Type Check -> Effect Check -> Contract Verify -> Hash -> JVM Codegen")
        return
    }

    val compiler = SigilCompiler()
    val result = compiler.compileSource(source)

    for (fn in result.functions) {
        println("Compiled: ${fn.className} (hash: ${fn.hash})")
        println("  Bytecode: ${fn.bytecode.size} bytes")
        println("  Verification tier: ${fn.verificationTier}")
    }

    println("\n${result.functions.size} function(s) compiled successfully.")
}
