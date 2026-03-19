package sigil.codegen.jvm

class JvmLinker {
    fun loadClasses(classes: Map<String, ByteArray>): ClassLoader {
        return ByteArrayClassLoader(classes, JvmLinker::class.java.classLoader)
    }
}

private class ByteArrayClassLoader(
    private val classBytes: Map<String, ByteArray>,
    parent: ClassLoader
) : ClassLoader(parent) {
    override fun findClass(name: String): Class<*> {
        val bytes = classBytes[name]
            ?: throw ClassNotFoundException(name)
        return defineClass(name, bytes, 0, bytes.size)
    }
}
