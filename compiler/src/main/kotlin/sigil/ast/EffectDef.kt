package sigil.ast

import kotlinx.serialization.Serializable

@Serializable
data class EffectOperation(
    val name: Alias,
    val params: List<Param>,
    val returnType: TypeRef
)

@Serializable
data class EffectDef(
    val name: Alias,
    val operations: List<EffectOperation>,
    val hash: Hash? = null
)

typealias EffectSet = Set<Hash>
