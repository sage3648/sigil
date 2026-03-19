package sigil.codegen.jvm

class LocalVarTable {
    private val vars = mutableMapOf<String, Int>()
    private var nextIndex = 0

    fun declare(name: String, isWide: Boolean = false): Int {
        val index = nextIndex
        vars[name] = index
        nextIndex += if (isWide) 2 else 1
        return index
    }

    fun alias(name: String, slot: Int) {
        vars[name] = slot
    }

    fun lookup(name: String): Int =
        vars[name] ?: throw CompileError("Undefined variable: $name")

    fun copy(): LocalVarTable {
        val copy = LocalVarTable()
        copy.vars.putAll(vars)
        copy.nextIndex = nextIndex
        return copy
    }
}
