package sigil.ast

import kotlinx.serialization.Serializable

@Serializable
data class FnSignature(
    val name: Alias,
    val params: List<Param>,
    val returnType: TypeRef,
    val effects: EffectSet = emptySet()
)

@Serializable
data class Property(
    val quantifiedVars: List<Param>,
    val predicate: ExprNode
)

@Serializable
data class TraitDef(
    val name: Alias,
    val methods: List<FnSignature>,
    val laws: List<Property>,
    val hash: Hash? = null
)
