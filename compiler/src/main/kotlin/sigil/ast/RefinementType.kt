package sigil.ast

import kotlinx.serialization.Serializable

@Serializable
data class RefinementType(
    val baseType: TypeRef,
    val predicate: ExprNode,
    val name: Alias? = null
)
