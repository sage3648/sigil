package sigil.codegen.jvm

import org.objectweb.asm.*
import org.objectweb.asm.Opcodes.*
import sigil.ast.*

class CompileError(message: String) : Exception(message)

class JvmCodegen {
    private val fnRegistry = mutableMapOf<Hash, FnLocation>()
    private val typeRegistry = mutableMapOf<Hash, String>()

    data class FnLocation(val className: String, val methodName: String, val descriptor: String)

    fun compileFnDef(fn: FnDef, hash: Hash): ByteArray {
        val className = "Sigil_${hash.take(8)}"
        val descriptor = methodDescriptor(fn.params, fn.returnType)

        fnRegistry[hash] = FnLocation(className, fn.name, descriptor)

        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        cw.visit(V17, ACC_PUBLIC or ACC_SUPER, className, null, "java/lang/Object", null)

        val mv = cw.visitMethod(
            ACC_PUBLIC or ACC_STATIC,
            fn.name,
            descriptor,
            null,
            null
        )
        mv.visitCode()

        val locals = LocalVarTable()
        for (param in fn.params) {
            if (isRefType(param.type)) {
                locals.declareRef(param.name)
            } else {
                locals.declare(param.name, isWide(param.type))
            }
        }

        // Emit requires contract checks
        fn.contract?.requires?.forEach { clause ->
            emitRequiresCheck(clause, mv, locals)
        }

        val hasEnsures = fn.contract?.ensures?.isNotEmpty() == true

        if (hasEnsures) {
            // Compile body, store result, check ensures, return result
            compileExpr(fn.body, mv, locals)
            val retType = fn.returnType
            val isWideRet = isWide(retType)
            val resultSlot = locals.declare("__result", isWideRet)

            if (isWideRet) mv.visitVarInsn(LSTORE, resultSlot)
            else mv.visitVarInsn(storeInsn(retType), resultSlot)

            fn.contract!!.ensures.forEach { clause ->
                emitEnsuresCheck(clause, mv, locals, resultSlot, retType)
            }

            if (isWideRet) mv.visitVarInsn(LLOAD, resultSlot)
            else mv.visitVarInsn(loadInsn(retType), resultSlot)

            mv.visitInsn(returnInsn(retType))
        } else {
            compileExpr(fn.body, mv, locals)
            mv.visitInsn(returnInsn(fn.returnType))
        }

        mv.visitMaxs(-1, -1)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    fun compileTypeDef(td: TypeDef, hash: Hash): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        val baseName = "Sigil_${hash.take(8)}"

        if (td.variants.size == 1) {
            // Product type: single data class
            val variant = td.variants[0]
            val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
            cw.visit(V17, ACC_PUBLIC or ACC_SUPER, baseName, null, "java/lang/Object", null)

            for (field in variant.fields) {
                cw.visitField(ACC_PUBLIC or ACC_FINAL, field.name, typeDescriptor(field.type), null, null).visitEnd()
            }

            // Constructor
            val initDesc = "(${variant.fields.joinToString("") { typeDescriptor(it.type) }})V"
            val mv = cw.visitMethod(ACC_PUBLIC, "<init>", initDesc, null, null)
            mv.visitCode()
            mv.visitVarInsn(ALOAD, 0)
            mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)

            var slot = 1
            for (field in variant.fields) {
                mv.visitVarInsn(ALOAD, 0)
                mv.visitVarInsn(loadInsnForDesc(typeDescriptor(field.type)), slot)
                mv.visitFieldInsn(PUTFIELD, baseName, field.name, typeDescriptor(field.type))
                slot += if (isWide(field.type)) 2 else 1
            }

            mv.visitInsn(RETURN)
            mv.visitMaxs(-1, -1)
            mv.visitEnd()
            cw.visitEnd()

            typeRegistry[hash] = baseName
            result[baseName] = cw.toByteArray()
        } else {
            // Sum type: sealed interface + variant classes
            val ifaceCw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
            ifaceCw.visit(V17, ACC_PUBLIC or ACC_ABSTRACT or ACC_INTERFACE, baseName, null, "java/lang/Object", null)
            ifaceCw.visitEnd()
            result[baseName] = ifaceCw.toByteArray()

            typeRegistry[hash] = baseName

            for (variant in td.variants) {
                val variantName = "${baseName}\$${variant.name}"
                val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
                cw.visit(V17, ACC_PUBLIC or ACC_SUPER, variantName, null, "java/lang/Object", arrayOf(baseName))

                for (field in variant.fields) {
                    cw.visitField(ACC_PUBLIC or ACC_FINAL, field.name, typeDescriptor(field.type), null, null).visitEnd()
                }

                val initDesc = "(${variant.fields.joinToString("") { typeDescriptor(it.type) }})V"
                val mv = cw.visitMethod(ACC_PUBLIC, "<init>", initDesc, null, null)
                mv.visitCode()
                mv.visitVarInsn(ALOAD, 0)
                mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)

                var slot = 1
                for (field in variant.fields) {
                    mv.visitVarInsn(ALOAD, 0)
                    mv.visitVarInsn(loadInsnForDesc(typeDescriptor(field.type)), slot)
                    mv.visitFieldInsn(PUTFIELD, variantName, field.name, typeDescriptor(field.type))
                    slot += if (isWide(field.type)) 2 else 1
                }

                mv.visitInsn(RETURN)
                mv.visitMaxs(-1, -1)
                mv.visitEnd()
                cw.visitEnd()

                result[variantName] = cw.toByteArray()
            }
        }

        return result
    }

    internal fun compileExpr(expr: ExprNode, mv: MethodVisitor, locals: LocalVarTable) {
        when (expr) {
            is ExprNode.Literal -> compileLiteral(expr.value, mv)
            is ExprNode.Ref -> compileRef(expr.hash, mv, locals)
            is ExprNode.Apply -> compileApply(expr, mv, locals)
            is ExprNode.Let -> compileLet(expr, mv, locals)
            is ExprNode.If -> compileIf(expr, mv, locals)
            is ExprNode.Block -> compileBlock(expr, mv, locals)
            is ExprNode.Match -> compileMatch(expr, mv, locals)
            is ExprNode.Lambda -> throw CompileError("Lambda compilation not yet supported")
        }
    }

    private fun compileLiteral(value: LiteralValue, mv: MethodVisitor) {
        when (value) {
            is LiteralValue.IntLit -> {
                mv.visitLdcInsn(value.value)
            }
            is LiteralValue.FloatLit -> {
                mv.visitLdcInsn(value.value)
            }
            is LiteralValue.BoolLit -> {
                mv.visitInsn(if (value.value) ICONST_1 else ICONST_0)
            }
            is LiteralValue.StringLit -> {
                mv.visitLdcInsn(value.value)
            }
            is LiteralValue.UnitLit -> {
                // Unit maps to void, nothing to push
            }
        }
    }

    private fun compileRef(hash: Hash, mv: MethodVisitor, locals: LocalVarTable) {
        // Check if it's a local variable name (params are registered by name)
        try {
            val slot = locals.lookup(hash)
            // It's a local variable - use the appropriate load instruction
            mv.visitVarInsn(locals.loadInsn(hash), slot)
            return
        } catch (_: CompileError) {
            // Not a local, might be a function ref
        }

        // Check the function registry
        val fnLoc = fnRegistry[hash]
        if (fnLoc != null) {
            // For now, refs to functions are resolved at call sites
            throw CompileError("Function reference without apply not yet supported: $hash")
        }

        throw CompileError("Unresolved reference: $hash")
    }

    private fun compileApply(expr: ExprNode.Apply, mv: MethodVisitor, locals: LocalVarTable) {
        val fn = expr.fn

        // Check for built-in binary operations
        if (fn is ExprNode.Ref && expr.args.size == 2) {
            val builtinOp = compileBuiltinBinaryOp(fn.hash, expr.args, mv, locals)
            if (builtinOp) return
        }

        // Check for built-in unary operations
        if (fn is ExprNode.Ref && expr.args.size == 1) {
            val builtinOp = compileBuiltinUnaryOp(fn.hash, expr.args[0], mv, locals)
            if (builtinOp) return
        }

        if (fn is ExprNode.Ref) {
            val fnLoc = fnRegistry[fn.hash]
                ?: throw CompileError("Unknown function: ${fn.hash}")

            // Compile all arguments onto the stack
            for (arg in expr.args) {
                compileExpr(arg, mv, locals)
            }

            mv.visitMethodInsn(
                INVOKESTATIC,
                fnLoc.className,
                fnLoc.methodName,
                fnLoc.descriptor,
                false
            )
        } else {
            throw CompileError("Non-ref function application not yet supported")
        }
    }

    private fun compileBuiltinBinaryOp(
        hash: Hash,
        args: List<ExprNode>,
        mv: MethodVisitor,
        locals: LocalVarTable
    ): Boolean {
        when (hash) {
            "#sigil:add" -> {
                compileExpr(args[0], mv, locals)
                compileExpr(args[1], mv, locals)
                mv.visitInsn(LADD)
            }
            "#sigil:sub" -> {
                compileExpr(args[0], mv, locals)
                compileExpr(args[1], mv, locals)
                mv.visitInsn(LSUB)
            }
            "#sigil:mul" -> {
                compileExpr(args[0], mv, locals)
                compileExpr(args[1], mv, locals)
                mv.visitInsn(LMUL)
            }
            "#sigil:div" -> {
                compileExpr(args[0], mv, locals)
                compileExpr(args[1], mv, locals)
                mv.visitInsn(LDIV)
            }
            "#sigil:gt" -> compileLongComparison(args, mv, locals, IFLE)
            "#sigil:lt" -> compileLongComparison(args, mv, locals, IFGE)
            "#sigil:eq" -> compileLongComparison(args, mv, locals, IFNE)
            "#sigil:gte" -> compileLongComparison(args, mv, locals, IFLT)
            "#sigil:lte" -> compileLongComparison(args, mv, locals, IFGT)
            "#sigil:neq" -> compileLongComparison(args, mv, locals, IFEQ)
            "#sigil:min" -> {
                compileExpr(args[0], mv, locals)
                compileExpr(args[1], mv, locals)
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "min", "(JJ)J", false)
            }
            "#sigil:max" -> {
                compileExpr(args[0], mv, locals)
                compileExpr(args[1], mv, locals)
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "max", "(JJ)J", false)
            }
            "#sigil:concat" -> {
                compileExpr(args[0], mv, locals)
                compileExpr(args[1], mv, locals)
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            }
            else -> return false
        }
        return true
    }

    private fun compileLongComparison(
        args: List<ExprNode>,
        mv: MethodVisitor,
        locals: LocalVarTable,
        falseOpcode: Int
    ) {
        compileExpr(args[0], mv, locals)
        compileExpr(args[1], mv, locals)
        mv.visitInsn(LCMP)
        val falseLabel = Label()
        val endLabel = Label()
        mv.visitJumpInsn(falseOpcode, falseLabel)
        mv.visitInsn(ICONST_1)
        mv.visitJumpInsn(GOTO, endLabel)
        mv.visitLabel(falseLabel)
        mv.visitInsn(ICONST_0)
        mv.visitLabel(endLabel)
    }

    private fun compileBuiltinUnaryOp(
        hash: Hash,
        arg: ExprNode,
        mv: MethodVisitor,
        locals: LocalVarTable
    ): Boolean {
        when (hash) {
            "#sigil:neg" -> {
                compileExpr(arg, mv, locals)
                mv.visitInsn(LNEG)
            }
            "#sigil:not" -> {
                compileExpr(arg, mv, locals)
                val falseLabel = Label()
                val endLabel = Label()
                mv.visitJumpInsn(IFNE, falseLabel)
                mv.visitInsn(ICONST_1)
                mv.visitJumpInsn(GOTO, endLabel)
                mv.visitLabel(falseLabel)
                mv.visitInsn(ICONST_0)
                mv.visitLabel(endLabel)
            }
            "#sigil:abs" -> {
                compileExpr(arg, mv, locals)
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "abs", "(J)J", false)
            }
            "#sigil:str_length" -> {
                compileExpr(arg, mv, locals)
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false)
                mv.visitInsn(I2L)
            }
            "#sigil:str_upper" -> {
                compileExpr(arg, mv, locals)
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "toUpperCase", "()Ljava/lang/String;", false)
            }
            "#sigil:str_lower" -> {
                compileExpr(arg, mv, locals)
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "toLowerCase", "()Ljava/lang/String;", false)
            }
            else -> return false
        }
        return true
    }

    private fun compileLet(expr: ExprNode.Let, mv: MethodVisitor, locals: LocalVarTable) {
        compileExpr(expr.value, mv, locals)
        // For now, assume all let bindings are longs (Int type)
        val slot = locals.declare(expr.binding, isWide = true)
        mv.visitVarInsn(LSTORE, slot)
        compileExpr(expr.body, mv, locals)
    }

    private fun compileIf(expr: ExprNode.If, mv: MethodVisitor, locals: LocalVarTable) {
        val elseLabel = Label()
        val endLabel = Label()

        compileExpr(expr.cond, mv, locals)
        mv.visitJumpInsn(IFEQ, elseLabel)

        compileExpr(expr.then_, mv, locals)
        mv.visitJumpInsn(GOTO, endLabel)

        mv.visitLabel(elseLabel)
        compileExpr(expr.else_, mv, locals)

        mv.visitLabel(endLabel)
    }

    private fun compileBlock(expr: ExprNode.Block, mv: MethodVisitor, locals: LocalVarTable) {
        if (expr.exprs.isEmpty()) return

        for (i in 0 until expr.exprs.size - 1) {
            compileExpr(expr.exprs[i], mv, locals)
            // Pop intermediate results - we'd need type info to know the width
            // For now, assume non-void intermediate expressions leave a value
            // This is a simplification; full implementation would track types
        }
        // Compile and keep the last expression's value
        compileExpr(expr.exprs.last(), mv, locals)
    }

    private fun compileMatch(expr: ExprNode.Match, mv: MethodVisitor, locals: LocalVarTable) {
        compileExpr(expr.scrutinee, mv, locals)
        val scrutineeSlot = locals.declare("__scrutinee", isWide = true)
        mv.visitVarInsn(LSTORE, scrutineeSlot)

        val endLabel = Label()

        for ((i, arm) in expr.arms.withIndex()) {
            val nextArmLabel = if (i < expr.arms.size - 1) Label() else null

            when (val pattern = arm.pattern) {
                is Pattern.WildcardPattern -> {
                    // Always matches
                    compileExpr(arm.body, mv, locals)
                    mv.visitJumpInsn(GOTO, endLabel)
                }
                is Pattern.LiteralPattern -> {
                    when (val lit = pattern.value) {
                        is LiteralValue.IntLit -> {
                            mv.visitVarInsn(LLOAD, scrutineeSlot)
                            mv.visitLdcInsn(lit.value)
                            mv.visitInsn(LCMP)
                            if (nextArmLabel != null) {
                                mv.visitJumpInsn(IFNE, nextArmLabel)
                            }
                            compileExpr(arm.body, mv, locals)
                            mv.visitJumpInsn(GOTO, endLabel)
                        }
                        is LiteralValue.BoolLit -> {
                            mv.visitVarInsn(LLOAD, scrutineeSlot)
                            mv.visitInsn(L2I)
                            mv.visitInsn(if (lit.value) ICONST_1 else ICONST_0)
                            if (nextArmLabel != null) {
                                mv.visitJumpInsn(IF_ICMPNE, nextArmLabel)
                            }
                            compileExpr(arm.body, mv, locals)
                            mv.visitJumpInsn(GOTO, endLabel)
                        }
                        else -> throw CompileError("Unsupported literal pattern type")
                    }
                }
                is Pattern.BindingPattern -> {
                    // Bind scrutinee to variable name
                    val bindSlot = locals.declare(pattern.name, isWide = true)
                    mv.visitVarInsn(LLOAD, scrutineeSlot)
                    mv.visitVarInsn(LSTORE, bindSlot)
                    compileExpr(arm.body, mv, locals)
                    mv.visitJumpInsn(GOTO, endLabel)
                }
                is Pattern.ConstructorPattern -> {
                    throw CompileError("Constructor pattern matching not yet supported in JVM codegen")
                }
                is Pattern.TuplePattern -> {
                    throw CompileError("Tuple pattern matching not yet supported in JVM codegen")
                }
            }

            if (nextArmLabel != null) {
                mv.visitLabel(nextArmLabel)
            }
        }

        mv.visitLabel(endLabel)
    }

    private fun emitRequiresCheck(clause: ContractClause, mv: MethodVisitor, locals: LocalVarTable) {
        val skipLabel = Label()
        compileExpr(clause.predicate, mv, locals)
        mv.visitJumpInsn(IFNE, skipLabel)
        mv.visitTypeInsn(NEW, "sigil/codegen/jvm/ContractViolation")
        mv.visitInsn(DUP)
        mv.visitLdcInsn("Requires contract violated")
        mv.visitMethodInsn(
            INVOKESPECIAL,
            "sigil/codegen/jvm/ContractViolation",
            "<init>",
            "(Ljava/lang/String;)V",
            false
        )
        mv.visitInsn(ATHROW)
        mv.visitLabel(skipLabel)
    }

    private fun emitEnsuresCheck(
        clause: ContractClause,
        mv: MethodVisitor,
        locals: LocalVarTable,
        resultSlot: Int,
        retType: TypeRef
    ) {
        // For ensures, the predicate might reference 'result'
        // We alias "result" to the __result slot so compileExpr can resolve it
        val skipLabel = Label()

        val resultLocals = locals.copy()
        resultLocals.alias("result", resultSlot)

        compileExpr(clause.predicate, mv, resultLocals)
        mv.visitJumpInsn(IFNE, skipLabel)
        mv.visitTypeInsn(NEW, "sigil/codegen/jvm/ContractViolation")
        mv.visitInsn(DUP)
        mv.visitLdcInsn("Ensures contract violated")
        mv.visitMethodInsn(
            INVOKESPECIAL,
            "sigil/codegen/jvm/ContractViolation",
            "<init>",
            "(Ljava/lang/String;)V",
            false
        )
        mv.visitInsn(ATHROW)
        mv.visitLabel(skipLabel)
    }

    fun typeDescriptor(typeRef: TypeRef): String {
        return when (typeRef.defHash) {
            PrimitiveTypes.INT -> "J"
            PrimitiveTypes.INT64 -> "J"
            PrimitiveTypes.INT32 -> "I"
            PrimitiveTypes.FLOAT64 -> "D"
            PrimitiveTypes.BOOL -> "Z"
            PrimitiveTypes.STRING -> "Ljava/lang/String;"
            PrimitiveTypes.UNIT -> "V"
            PrimitiveTypes.LIST -> "Ljava/util/List;"
            PrimitiveTypes.MAP -> "Ljava/util/Map;"
            PrimitiveTypes.OPTION -> "Ljava/lang/Object;"
            PrimitiveTypes.BYTES -> "[B"
            else -> {
                val className = typeRegistry[typeRef.defHash]
                if (className != null) "L$className;"
                else "Ljava/lang/Object;"
            }
        }
    }

    private fun methodDescriptor(params: List<Param>, returnType: TypeRef): String {
        val paramDescs = params.joinToString("") { typeDescriptor(it.type) }
        val retDesc = typeDescriptor(returnType)
        return "($paramDescs)$retDesc"
    }

    private fun isWide(typeRef: TypeRef): Boolean =
        typeRef.defHash == PrimitiveTypes.INT ||
        typeRef.defHash == PrimitiveTypes.INT64 ||
        typeRef.defHash == PrimitiveTypes.FLOAT64

    private fun isRefType(typeRef: TypeRef): Boolean =
        typeRef.defHash == PrimitiveTypes.STRING ||
        typeRef.defHash == PrimitiveTypes.BYTES ||
        typeRef.defHash == PrimitiveTypes.LIST ||
        typeRef.defHash == PrimitiveTypes.MAP ||
        typeRef.defHash == PrimitiveTypes.OPTION ||
        (!isWide(typeRef) && typeRef.defHash != PrimitiveTypes.BOOL && typeRef.defHash != PrimitiveTypes.INT32 && typeRef.defHash != PrimitiveTypes.UNIT)

    private fun returnInsn(typeRef: TypeRef): Int = when (typeRef.defHash) {
        PrimitiveTypes.INT, PrimitiveTypes.INT64 -> LRETURN
        PrimitiveTypes.FLOAT64 -> DRETURN
        PrimitiveTypes.INT32 -> IRETURN
        PrimitiveTypes.BOOL -> IRETURN
        PrimitiveTypes.UNIT -> RETURN
        else -> ARETURN
    }

    private fun storeInsn(typeRef: TypeRef): Int = when (typeRef.defHash) {
        PrimitiveTypes.INT, PrimitiveTypes.INT64 -> LSTORE
        PrimitiveTypes.FLOAT64 -> DSTORE
        PrimitiveTypes.INT32 -> ISTORE
        PrimitiveTypes.BOOL -> ISTORE
        else -> ASTORE
    }

    private fun loadInsn(typeRef: TypeRef): Int = when (typeRef.defHash) {
        PrimitiveTypes.INT, PrimitiveTypes.INT64 -> LLOAD
        PrimitiveTypes.FLOAT64 -> DLOAD
        PrimitiveTypes.INT32 -> ILOAD
        PrimitiveTypes.BOOL -> ILOAD
        else -> ALOAD
    }

    private fun loadInsnForDesc(desc: String): Int = when (desc) {
        "J" -> LLOAD
        "D" -> DLOAD
        "I", "Z" -> ILOAD
        else -> ALOAD
    }
}
