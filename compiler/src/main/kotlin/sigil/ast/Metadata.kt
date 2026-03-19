package sigil.ast

import kotlinx.serialization.Serializable

@Serializable
data class SemanticMeta(
    val intent: String? = null,
    val author: String? = null,
    val tradeoffs: List<String> = emptyList(),
    val domainTags: List<String> = emptyList()
)

@Serializable
data class VerificationRecord(
    val tier: Int,
    val tool: String,
    val timestamp: Long,
    val details: Map<String, String> = emptyMap()
)
