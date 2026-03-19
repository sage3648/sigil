package sigil.types

import sigil.ast.TypeRef

sealed class Type {
    data class Concrete(val hash: String, val args: List<Type> = emptyList()) : Type()
    data class Variable(val id: Int) : Type()
    data class Function(val params: List<Type>, val returnType: Type, val effects: Set<String> = emptySet()) : Type()
}

class UnificationError(message: String) : Exception(message)

class Unifier {
    private var nextId = 0
    private val substitutions = mutableMapOf<Int, Type>()

    fun freshVar(): Type.Variable = Type.Variable(nextId++)

    fun unify(a: Type, b: Type) {
        val ra = resolve(a)
        val rb = resolve(b)

        when {
            ra == rb -> return
            ra is Type.Variable -> bind(ra.id, rb)
            rb is Type.Variable -> bind(rb.id, ra)
            ra is Type.Concrete && rb is Type.Concrete -> {
                if (ra.hash != rb.hash) {
                    throw UnificationError("Cannot unify ${ra.hash} with ${rb.hash}")
                }
                if (ra.args.size != rb.args.size) {
                    throw UnificationError("Type argument count mismatch for ${ra.hash}: ${ra.args.size} vs ${rb.args.size}")
                }
                ra.args.zip(rb.args).forEach { (argA, argB) -> unify(argA, argB) }
            }
            ra is Type.Function && rb is Type.Function -> {
                if (ra.params.size != rb.params.size) {
                    throw UnificationError("Function parameter count mismatch: ${ra.params.size} vs ${rb.params.size}")
                }
                ra.params.zip(rb.params).forEach { (pa, pb) -> unify(pa, pb) }
                unify(ra.returnType, rb.returnType)
            }
            else -> throw UnificationError("Cannot unify ${typeToString(ra)} with ${typeToString(rb)}")
        }
    }

    fun resolve(type: Type): Type = when (type) {
        is Type.Variable -> {
            val sub = substitutions[type.id]
            if (sub != null) {
                val resolved = resolve(sub)
                substitutions[type.id] = resolved
                resolved
            } else {
                type
            }
        }
        is Type.Concrete -> Type.Concrete(type.hash, type.args.map { resolve(it) })
        is Type.Function -> Type.Function(
            type.params.map { resolve(it) },
            resolve(type.returnType),
            type.effects
        )
    }

    fun typeRefToType(ref: TypeRef): Type {
        return Type.Concrete(ref.defHash, ref.args.map { typeRefToType(it) })
    }

    fun typeToTypeRef(type: Type): TypeRef {
        val resolved = resolve(type)
        return when (resolved) {
            is Type.Concrete -> TypeRef(resolved.hash, resolved.args.map { typeToTypeRef(it) })
            is Type.Variable -> throw UnificationError("Cannot convert unresolved type variable ?${resolved.id} to TypeRef")
            is Type.Function -> throw UnificationError("Cannot convert function type to TypeRef directly")
        }
    }

    private fun bind(id: Int, type: Type) {
        if (occursIn(id, type)) {
            throw UnificationError("Infinite type: ?$id occurs in ${typeToString(type)}")
        }
        substitutions[id] = type
    }

    private fun occursIn(id: Int, type: Type): Boolean = when (val t = resolve(type)) {
        is Type.Variable -> t.id == id
        is Type.Concrete -> t.args.any { occursIn(id, it) }
        is Type.Function -> t.params.any { occursIn(id, it) } || occursIn(id, t.returnType)
    }

    private fun typeToString(type: Type): String = when (type) {
        is Type.Variable -> "?${type.id}"
        is Type.Concrete -> if (type.args.isEmpty()) type.hash else "${type.hash}<${type.args.joinToString(", ") { typeToString(it) }}>"
        is Type.Function -> "(${type.params.joinToString(", ") { typeToString(it) }}) -> ${typeToString(type.returnType)}"
    }
}
