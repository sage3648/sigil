package sigil.ast

import kotlinx.serialization.Serializable

@Serializable
data class Variant(
    val name: Alias,
    val fields: List<Param>
)

@Serializable
data class TypeDef(
    val name: Alias,
    val typeParams: List<TypeVar>,
    val variants: List<Variant>,
    val hash: Hash? = null
)
