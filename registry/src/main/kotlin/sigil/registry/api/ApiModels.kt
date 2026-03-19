package sigil.registry.api

import kotlinx.serialization.Serializable
import sigil.registry.store.NodeMetadata
import sigil.registry.store.SemanticSignature

@Serializable
data class PublishRequest(
    val source: String,
    val metadata: PublishMetadata = PublishMetadata()
)

@Serializable
data class PublishMetadata(
    val aliases: List<String> = emptyList(),
    val intent: String? = null,
    val author: String? = null,
    val domainTags: List<String> = emptyList()
)

@Serializable
data class PublishResponse(
    val hash: String,
    val nodeType: String,
    val verificationTier: Int,
    val verificationDetails: List<VerificationDetail> = emptyList()
)

@Serializable
data class VerificationDetail(
    val tier: Int,
    val tool: String,
    val status: String
)

@Serializable
data class SearchRequest(
    val inputTypes: List<String>? = null,
    val outputType: String? = null,
    val ensuresContains: List<String>? = null,
    val effects: String? = null,
    val minVerificationTier: Int = 0,
    val domainTags: List<String>? = null,
    val textQuery: String? = null,
    val limit: Int = 20
)

@Serializable
data class NodeResponse(
    val hash: String,
    val nodeType: String,
    val ast: String,
    val semanticSignature: SemanticSignature?,
    val metadata: NodeMetadata,
    val verification: List<VerificationDetail> = emptyList(),
    val dependents: List<String> = emptyList(),
    val dependencies: List<String> = emptyList()
)

@Serializable
data class StatsResponse(
    val totalNodes: Int,
    val countsByType: Map<String, Int>,
    val countsByTier: Map<Int, Int>
)

@Serializable
data class DeprecationResponse(
    val deprecatedHash: String,
    val affectedNodes: List<String>,
    val reverificationNeeded: List<String>
)

@Serializable
data class ErrorResponse(
    val message: String,
    val details: String? = null
)
