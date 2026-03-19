package sigil.registry.semantic

import kotlinx.serialization.Serializable
import sigil.ast.Hash
import sigil.registry.store.NodeMetadata
import sigil.registry.store.RegistryStore
import sigil.registry.store.SemanticSignature

@Serializable
data class SearchQuery(
    val inputTypes: List<String>? = null,
    val outputType: String? = null,
    val ensuresContains: List<String>? = null,
    val effects: String? = null,
    val minVerificationTier: Int = 0,
    val domainTags: List<String>? = null,
    val textQuery: String? = null
)

@Serializable
data class SearchResult(
    val hash: Hash,
    val nodeType: String,
    val semanticSignature: SemanticSignature?,
    val metadata: NodeMetadata,
    val verificationTier: Int,
    val score: Double
)

class SemanticSearch(private val store: RegistryStore) {

    suspend fun search(query: SearchQuery, limit: Int = 20): List<SearchResult> {
        val allNodes = store.listAll(limit = Int.MAX_VALUE)

        return allNodes.mapNotNull { node ->
            val sig = node.semanticSignature
            val maxTier = if (node.verification.isEmpty()) 0 else node.verification.maxOf { it.tier }

            if (maxTier < query.minVerificationTier && query.minVerificationTier > 0) {
                return@mapNotNull null
            }

            var score = 0.0

            if (query.inputTypes != null && sig != null) {
                val inputScore = computeTypeListScore(query.inputTypes, sig.inputTypes)
                if (inputScore == 0.0 && query.inputTypes.isNotEmpty()) return@mapNotNull null
                score += inputScore * 10.0
            }

            if (query.outputType != null && sig != null) {
                val outScore = computeTypeMatchScore(query.outputType, sig.outputType)
                if (outScore == 0.0) return@mapNotNull null
                score += outScore * 10.0
            }

            if (query.ensuresContains != null && sig != null) {
                val ensuresStr = sig.ensuresPredicates.joinToString(" ")
                var matched = 0
                for (pred in query.ensuresContains) {
                    if (ensuresStr.contains(pred, ignoreCase = true)) matched++
                }
                if (matched == 0 && query.ensuresContains.isNotEmpty()) return@mapNotNull null
                score += matched.toDouble() / query.ensuresContains.size * 8.0
            }

            if (query.effects != null && sig != null) {
                val wantPure = query.effects.equals("pure", ignoreCase = true)
                val isPure = sig.effects.isEmpty()
                if (wantPure == isPure) {
                    score += 5.0
                } else {
                    val effectMatch = sig.effects.any { it.contains(query.effects, ignoreCase = true) }
                    if (effectMatch) score += 3.0
                }
            }

            if (query.domainTags != null) {
                val matched = query.domainTags.count { tag ->
                    node.metadata.domainTags.any { it.equals(tag, ignoreCase = true) }
                }
                score += matched.toDouble() * 2.0
            }

            if (query.textQuery != null) {
                val text = query.textQuery.lowercase()
                val searchable = buildString {
                    append(node.metadata.aliases.joinToString(" "))
                    append(" ")
                    append(node.metadata.intent ?: "")
                    append(" ")
                    if (sig != null) {
                        append(sig.inputTypes.joinToString(" "))
                        append(" ")
                        append(sig.outputType)
                    }
                }.lowercase()
                if (searchable.contains(text)) {
                    score += 4.0
                }
            }

            // Tier boost
            score += maxTier * 1.0

            if (score > 0.0) {
                SearchResult(
                    hash = node.hash,
                    nodeType = node.nodeType,
                    semanticSignature = node.semanticSignature,
                    metadata = node.metadata,
                    verificationTier = maxTier,
                    score = score
                )
            } else null
        }
            .sortedByDescending { it.score }
            .take(limit)
    }

    companion object {
        fun computeTypeListScore(queryTypes: List<String>, actualTypes: List<String>): Double {
            if (queryTypes.isEmpty()) return 1.0
            if (actualTypes.isEmpty()) return 0.0

            var matched = 0
            val remaining = actualTypes.toMutableList()
            for (qt in queryTypes) {
                val idx = remaining.indexOfFirst { computeTypeMatchScore(qt, it) > 0.0 }
                if (idx >= 0) {
                    matched++
                    remaining.removeAt(idx)
                }
            }
            return matched.toDouble() / queryTypes.size
        }

        fun computeTypeMatchScore(query: String, actual: String): Double {
            if (query.equals(actual, ignoreCase = true)) return 1.0
            // Partial match: query is a substring of actual or vice versa
            if (actual.contains(query, ignoreCase = true) || query.contains(actual, ignoreCase = true)) return 0.5
            return 0.0
        }
    }
}
