package sigil.registry.store

import kotlinx.serialization.Serializable
import sigil.ast.Hash
import sigil.ast.VerificationRecord

@Serializable
data class RegistryNode(
    val hash: Hash,
    val nodeType: String,
    val ast: String,
    val semanticSignature: SemanticSignature?,
    val metadata: NodeMetadata,
    val verification: List<VerificationRecord> = emptyList(),
    val dependents: List<Hash> = emptyList(),
    val dependencies: List<Hash> = emptyList()
)

@Serializable
data class NodeMetadata(
    val aliases: List<String> = emptyList(),
    val intent: String? = null,
    val author: String? = null,
    val createdAt: Long,
    val domainTags: List<String> = emptyList()
)

@Serializable
data class SemanticSignature(
    val inputTypes: List<String> = emptyList(),
    val outputType: String,
    val effects: List<String> = emptyList(),
    val requiresPredicates: List<String> = emptyList(),
    val ensuresPredicates: List<String> = emptyList()
)
