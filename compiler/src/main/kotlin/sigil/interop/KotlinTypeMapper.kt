package sigil.interop

import sigil.ast.PrimitiveTypes
import sigil.ast.TypeRef
import kotlin.reflect.KClass

object KotlinTypeMapper {

    fun sigilToKotlin(typeRef: TypeRef): KClass<*> = when (typeRef.defHash) {
        PrimitiveTypes.INT, PrimitiveTypes.INT64 -> Long::class
        PrimitiveTypes.INT32 -> Int::class
        PrimitiveTypes.FLOAT64 -> Double::class
        PrimitiveTypes.BOOL -> Boolean::class
        PrimitiveTypes.STRING -> String::class
        PrimitiveTypes.UNIT -> Unit::class
        else -> Any::class
    }

    fun sigilToJvmClass(typeRef: TypeRef): Class<*> = when (typeRef.defHash) {
        PrimitiveTypes.INT, PrimitiveTypes.INT64 -> Long::class.java
        PrimitiveTypes.INT32 -> Int::class.java
        PrimitiveTypes.FLOAT64 -> Double::class.java
        PrimitiveTypes.BOOL -> Boolean::class.java
        PrimitiveTypes.STRING -> String::class.java
        PrimitiveTypes.UNIT -> Void.TYPE
        else -> Any::class.java
    }

    fun kotlinToSigil(kClass: KClass<*>): TypeRef = when (kClass) {
        Long::class -> TypeRef(PrimitiveTypes.INT)
        Int::class -> TypeRef(PrimitiveTypes.INT32)
        Double::class -> TypeRef(PrimitiveTypes.FLOAT64)
        Boolean::class -> TypeRef(PrimitiveTypes.BOOL)
        String::class -> TypeRef(PrimitiveTypes.STRING)
        Unit::class -> TypeRef(PrimitiveTypes.UNIT)
        else -> throw IllegalArgumentException("No Sigil mapping for ${kClass.qualifiedName}")
    }

    fun jvmDescriptor(typeRef: TypeRef): String = when (typeRef.defHash) {
        PrimitiveTypes.INT, PrimitiveTypes.INT64 -> "J"
        PrimitiveTypes.INT32 -> "I"
        PrimitiveTypes.FLOAT64 -> "D"
        PrimitiveTypes.BOOL -> "Z"
        PrimitiveTypes.STRING -> "Ljava/lang/String;"
        PrimitiveTypes.UNIT -> "V"
        PrimitiveTypes.LIST -> "Ljava/util/List;"
        PrimitiveTypes.MAP -> "Ljava/util/Map;"
        PrimitiveTypes.BYTES -> "[B"
        else -> "Ljava/lang/Object;"
    }
}
