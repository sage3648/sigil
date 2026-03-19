package sigil.ast

import kotlinx.serialization.Serializable

@Serializable
data class EffectHandler(
    val name: Alias,
    val handles: Hash,
    val implementations: Map<Alias, ExprNode>
)
