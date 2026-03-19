package sigil.api

import sigil.ast.*
import sigil.hash.Hasher
import sigil.parser.parse
import java.io.File

class CliException(message: String, val exitCode: Int = 1) : RuntimeException(message)

private fun cliError(message: String): Nothing {
    System.err.println(message)
    throw CliException(message)
}

object Cli {

    fun run(args: Array<String>) {
        if (args.isEmpty()) {
            printHelp()
            return
        }

        when (args[0]) {
            "compile" -> compile(args.drop(1))
            "run" -> runCommand(args.drop(1))
            "verify" -> verify(args.drop(1))
            "inspect" -> inspect(args.drop(1))
            "help", "--help", "-h" -> printHelp()
            else -> cliError("Unknown command: ${args[0]}\nRun 'sigil help' for usage information.")
        }
    }

    private fun compile(args: List<String>) {
        var outputDir = "./out"
        val positional = mutableListOf<String>()

        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--output-dir" -> {
                    if (i + 1 >= args.size) {
                        cliError("Error: --output-dir requires a value")
                    }
                    outputDir = args[++i]
                }
                else -> positional.add(args[i])
            }
            i++
        }

        if (positional.isEmpty()) {
            cliError("Error: compile requires a file argument\nUsage: sigil compile <file> [--output-dir <dir>]")
        }

        val file = File(positional[0])
        if (!file.exists()) {
            cliError("Error: file not found: ${file.path}")
        }

        val source = file.readText()
        val compiler = SigilCompiler()
        val result = compiler.compileSource(source)

        val outDir = File(outputDir)
        outDir.mkdirs()

        for (fn in result.functions) {
            val classFile = File(outDir, "${fn.className}.class")
            classFile.writeBytes(fn.bytecode)
            println("Compiled: ${fn.className}")
            println("  Hash: ${fn.hash}")
            println("  Verification tier: ${fn.verificationTier}")
            println("  Output: ${classFile.path}")
        }

        println("\n${result.functions.size} function(s) compiled successfully.")
    }

    private fun runCommand(args: List<String>) {
        if (args.size < 2) {
            cliError("Error: run requires a file and function name\nUsage: sigil run <file> <function> [args...]")
        }

        val file = File(args[0])
        if (!file.exists()) {
            cliError("Error: file not found: ${file.path}")
        }

        val functionName = args[1]
        val fnArgs = args.drop(2)

        val source = file.readText()
        val compiler = SigilCompiler()
        val result = compiler.compileSource(source)

        // Find the compiled function by name
        val parsed = parse(source)
        val fnDefs = parsed.filterIsInstance<FnDef>()
        val targetFn = fnDefs.find { it.name == functionName }
        if (targetFn == null) {
            cliError("Error: function '$functionName' not found\nAvailable functions: ${fnDefs.joinToString(", ") { it.name }}")
        }

        if (fnArgs.size != targetFn.params.size) {
            cliError("Error: function '$functionName' expects ${targetFn.params.size} argument(s), got ${fnArgs.size}")
        }

        // Find the corresponding compilation result
        val hash = Hasher.hashFnDef(targetFn)
        val className = "Sigil_${hash.take(8)}"

        val clazz = result.classLoader.loadClass(className)

        // Build parameter types for reflection
        val paramTypes = targetFn.params.map { jvmClass(it.type) }.toTypedArray()
        val method = clazz.getMethod(functionName, *paramTypes)

        // Parse CLI args to appropriate types
        val invokeArgs = fnArgs.zip(targetFn.params).map { (arg, param) ->
            parseArg(arg, param.type)
        }.toTypedArray()

        val returnValue = method.invoke(null, *invokeArgs)
        if (targetFn.returnType.defHash != PrimitiveTypes.UNIT) {
            println(returnValue)
        }
    }

    private fun verify(args: List<String>) {
        var verbose = false
        val positional = mutableListOf<String>()

        for (arg in args) {
            when (arg) {
                "--verbose", "-v" -> verbose = true
                else -> positional.add(arg)
            }
        }

        if (positional.isEmpty()) {
            cliError("Error: verify requires a file argument\nUsage: sigil verify <file> [--verbose]")
        }

        val file = File(positional[0])
        if (!file.exists()) {
            cliError("Error: file not found: ${file.path}")
        }

        val source = file.readText()
        val compiler = SigilCompiler()
        val result = compiler.compileSource(source)
        val parsed = parse(source)
        val fnDefs = parsed.filterIsInstance<FnDef>()

        println("Verification Report: ${file.name}")
        println("=" .repeat(40))

        for ((fn, compResult) in fnDefs.zip(result.functions)) {
            println("\nFunction: ${fn.name}")
            println("  Type: (${fn.params.joinToString(", ") { "${it.name}: ${formatType(it.type)}" }}) -> ${formatType(fn.returnType)}")

            if (fn.effects.isNotEmpty()) {
                println("  Effects: ${fn.effects.joinToString(", ")}")
            }

            val contractStatus = when {
                fn.contract == null -> "none"
                fn.contract!!.requires.isNotEmpty() && fn.contract!!.ensures.isNotEmpty() ->
                    "${fn.contract!!.requires.size} requires, ${fn.contract!!.ensures.size} ensures"
                fn.contract!!.requires.isNotEmpty() -> "${fn.contract!!.requires.size} requires"
                else -> "${fn.contract!!.ensures.size} ensures"
            }
            println("  Contracts: $contractStatus")
            println("  Verification tier: ${compResult.verificationTier}")

            if (verbose) {
                println("  Hash: ${compResult.hash}")
                println("  Bytecode size: ${compResult.bytecode.size} bytes")
            }
        }

        println("\n${fnDefs.size} function(s) verified.")
    }

    private fun inspect(args: List<String>) {
        if (args.isEmpty()) {
            cliError("Error: inspect requires a file argument\nUsage: sigil inspect <file>")
        }

        val file = File(args[0])
        if (!file.exists()) {
            cliError("Error: file not found: ${file.path}")
        }

        val source = file.readText()
        val parsed = parse(source)

        println("AST Inspection: ${file.name}")
        println("=" .repeat(40))

        for (item in parsed) {
            when (item) {
                is FnDef -> {
                    val hash = Hasher.hashFnDef(item)
                    println("\nFunction: ${item.name}")
                    println("  Params: ${item.params.joinToString(", ") { "${it.name}: ${formatType(it.type)}" }}")
                    println("  Returns: ${formatType(item.returnType)}")
                    if (item.contract != null) {
                        println("  Contracts:")
                        for (req in item.contract!!.requires) {
                            println("    requires: ${formatExpr(req.predicate)}")
                        }
                        for (ens in item.contract!!.ensures) {
                            println("    ensures: ${formatExpr(ens.predicate)}")
                        }
                    }
                    if (item.effects.isNotEmpty()) {
                        println("  Effects: ${item.effects.joinToString(", ")}")
                    }
                    println("  Hash: $hash")
                }
                is TypeDef -> {
                    val hash = Hasher.hashTypeDef(item)
                    println("\nType: ${item.name}")
                    for (variant in item.variants) {
                        val fields = if (variant.fields.isNotEmpty()) {
                            "(${variant.fields.joinToString(", ") { "${it.name}: ${formatType(it.type)}" }})"
                        } else ""
                        println("  | ${variant.name}$fields")
                    }
                    println("  Hash: $hash")
                }
                is EffectDef -> {
                    println("\nEffect: ${item.name}")
                    for (op in item.operations) {
                        println("  fn ${op.name}(${op.params.joinToString(", ") { "${it.name}: ${formatType(it.type)}" }}) -> ${formatType(op.returnType)}")
                    }
                }
            }
        }
    }

    private fun printHelp() {
        println("Sigil Compiler v0.1.0")
        println()
        println("Usage: sigil <command> [options]")
        println()
        println("Commands:")
        println("  compile <file> [--output-dir <dir>]   Compile a .sigil file to JVM bytecode")
        println("  run <file> <function> [args...]       Compile and execute a function")
        println("  verify <file> [--verbose]             Verify without codegen")
        println("  inspect <file>                        Show AST details")
        println("  help                                  Show this help message")
        println()
        println("Examples:")
        println("  sigil compile math.sigil")
        println("  sigil compile math.sigil --output-dir build/")
        println("  sigil run math.sigil add 3 4")
        println("  sigil verify math.sigil --verbose")
        println("  sigil inspect math.sigil")
    }

    private fun jvmClass(typeRef: TypeRef): Class<*> = when (typeRef.defHash) {
        PrimitiveTypes.INT, PrimitiveTypes.INT64 -> Long::class.java
        PrimitiveTypes.INT32 -> Int::class.java
        PrimitiveTypes.FLOAT64 -> Double::class.java
        PrimitiveTypes.BOOL -> Boolean::class.java
        PrimitiveTypes.STRING -> String::class.java
        else -> Any::class.java
    }

    private fun parseArg(arg: String, type: TypeRef): Any = when (type.defHash) {
        PrimitiveTypes.INT, PrimitiveTypes.INT64 -> arg.toLong()
        PrimitiveTypes.INT32 -> arg.toInt()
        PrimitiveTypes.FLOAT64 -> arg.toDouble()
        PrimitiveTypes.BOOL -> arg.toBoolean()
        PrimitiveTypes.STRING -> arg
        else -> arg
    }

    private fun formatType(typeRef: TypeRef): String {
        val name = when (typeRef.defHash) {
            PrimitiveTypes.INT -> "Int"
            PrimitiveTypes.INT32 -> "I32"
            PrimitiveTypes.INT64 -> "I64"
            PrimitiveTypes.FLOAT64 -> "F64"
            PrimitiveTypes.BOOL -> "Bool"
            PrimitiveTypes.STRING -> "String"
            PrimitiveTypes.UNIT -> "Unit"
            PrimitiveTypes.BYTES -> "Bytes"
            PrimitiveTypes.LIST -> "List"
            PrimitiveTypes.MAP -> "Map"
            PrimitiveTypes.OPTION -> "Option"
            PrimitiveTypes.RESULT -> "Result"
            else -> typeRef.defHash
        }
        return if (typeRef.args.isNotEmpty()) {
            "$name<${typeRef.args.joinToString(", ") { formatType(it) }}>"
        } else name
    }

    private val binaryOpNames = mapOf(
        "#sigil:add" to "+", "#sigil:sub" to "-", "#sigil:mul" to "*", "#sigil:div" to "/",
        "#sigil:mod" to "%", "#sigil:eq" to "==", "#sigil:neq" to "!=",
        "#sigil:lt" to "<", "#sigil:gt" to ">", "#sigil:lte" to "<=", "#sigil:gte" to ">=",
        "#sigil:and" to "&&", "#sigil:or" to "||", "#sigil:concat" to "++"
    )

    private fun formatExpr(expr: ExprNode): String = when (expr) {
        is ExprNode.Literal -> when (val v = expr.value) {
            is LiteralValue.IntLit -> v.value.toString()
            is LiteralValue.FloatLit -> v.value.toString()
            is LiteralValue.StringLit -> "\"${v.value}\""
            is LiteralValue.BoolLit -> v.value.toString()
            is LiteralValue.UnitLit -> "()"
        }
        is ExprNode.Ref -> expr.hash
        is ExprNode.Apply -> {
            if (expr.fn is ExprNode.Ref && expr.args.size == 2 && expr.fn.hash in binaryOpNames) {
                "(${formatExpr(expr.args[0])} ${binaryOpNames[expr.fn.hash]} ${formatExpr(expr.args[1])})"
            } else {
                "${formatExpr(expr.fn)}(${expr.args.joinToString(", ") { formatExpr(it) }})"
            }
        }
        is ExprNode.If -> "if ${formatExpr(expr.cond)} then ${formatExpr(expr.then_)} else ${formatExpr(expr.else_)}"
        is ExprNode.Let -> "let ${expr.binding} = ${formatExpr(expr.value)}; ${formatExpr(expr.body)}"
        is ExprNode.Lambda -> "|...| ${formatExpr(expr.body)}"
        is ExprNode.Match -> "match ${formatExpr(expr.scrutinee)} { ... }"
        is ExprNode.Block -> expr.exprs.joinToString("; ") { formatExpr(it) }
    }
}
