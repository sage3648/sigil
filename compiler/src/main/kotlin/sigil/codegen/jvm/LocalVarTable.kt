package sigil.codegen.jvm

import org.objectweb.asm.Opcodes

class LocalVarTable {
    private val vars = mutableMapOf<String, Int>()
    private val loadOps = mutableMapOf<String, Int>()
    private val storeOps = mutableMapOf<String, Int>()
    private var nextIndex = 0

    fun declare(name: String, isWide: Boolean = false): Int {
        val index = nextIndex
        vars[name] = index
        if (isWide) {
            loadOps[name] = Opcodes.LLOAD
            storeOps[name] = Opcodes.LSTORE
        } else {
            loadOps[name] = Opcodes.ILOAD
            storeOps[name] = Opcodes.ISTORE
        }
        nextIndex += if (isWide) 2 else 1
        return index
    }

    fun declareRef(name: String): Int {
        val index = nextIndex
        vars[name] = index
        loadOps[name] = Opcodes.ALOAD
        storeOps[name] = Opcodes.ASTORE
        nextIndex += 1
        return index
    }

    fun alias(name: String, slot: Int) {
        vars[name] = slot
    }

    fun lookup(name: String): Int =
        vars[name] ?: throw CompileError("Undefined variable: $name")

    fun loadInsn(name: String): Int =
        loadOps[name] ?: Opcodes.LLOAD

    fun storeInsn(name: String): Int =
        storeOps[name] ?: Opcodes.LSTORE

    fun copy(): LocalVarTable {
        val copy = LocalVarTable()
        copy.vars.putAll(vars)
        copy.loadOps.putAll(loadOps)
        copy.storeOps.putAll(storeOps)
        copy.nextIndex = nextIndex
        return copy
    }
}
