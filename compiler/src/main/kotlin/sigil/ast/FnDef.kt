package sigil.ast

import kotlinx.serialization.Serializable

@Serializable
data class FnDef(
    val name: Alias,
    val params: List<Param>,
    val returnType: TypeRef,
    val contract: ContractNode? = null,
    val effects: EffectSet = emptySet(),
    val body: ExprNode,
    val hash: Hash? = null,
    val metadata: SemanticMeta? = null,
    val verification: List<VerificationRecord> = emptyList()
)
