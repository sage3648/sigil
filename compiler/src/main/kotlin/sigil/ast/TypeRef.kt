package sigil.ast

import kotlinx.serialization.Serializable

@Serializable
data class TypeRef(
    val defHash: Hash,
    val args: List<TypeRef> = emptyList()
)

@Serializable
data class TypeVar(
    val name: Alias,
    val bounds: List<Hash> = emptyList()
)
