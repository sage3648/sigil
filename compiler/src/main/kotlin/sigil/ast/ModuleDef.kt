package sigil.ast

import kotlinx.serialization.Serializable

@Serializable
data class ModuleDef(
    val name: Alias,
    val exports: List<Hash>,
    val definitions: List<Hash>,
    val metadata: SemanticMeta? = null,
    val hash: Hash? = null
)
