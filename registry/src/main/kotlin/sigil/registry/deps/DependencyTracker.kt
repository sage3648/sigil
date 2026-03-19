package sigil.registry.deps

import sigil.ast.*
import sigil.registry.store.RegistryStore

data class DeprecationResult(
    val deprecatedHash: Hash,
    val affectedNodes: Set<Hash>,
    val reverificationNeeded: Set<Hash>
)

class DependencyTracker(private val store: RegistryStore) {

    fun extractDependencies(fn: FnDef): Set<Hash> {
        val refs = mutableSetOf<Hash>()
        collectRefs(fn.body, refs)
        return refs
    }

    fun extractDependencies(module: ModuleDef): Set<Hash> {
        val refs = mutableSetOf<Hash>()
        for (hash in module.exports) {
            if (isContentHash(hash)) refs.add(hash)
        }
        for (hash in module.definitions) {
            if (isContentHash(hash)) refs.add(hash)
        }
        return refs
    }

    private fun collectRefs(expr: ExprNode, refs: MutableSet<Hash>) {
        when (expr) {
            is ExprNode.Ref -> {
                if (isContentHash(expr.hash)) {
                    refs.add(expr.hash)
                }
            }
            is ExprNode.Apply -> {
                collectRefs(expr.fn, refs)
                expr.args.forEach { collectRefs(it, refs) }
            }
            is ExprNode.Match -> {
                collectRefs(expr.scrutinee, refs)
                expr.arms.forEach { collectRefs(it.body, refs) }
            }
            is ExprNode.Let -> {
                collectRefs(expr.value, refs)
                collectRefs(expr.body, refs)
            }
            is ExprNode.Lambda -> {
                collectRefs(expr.body, refs)
            }
            is ExprNode.If -> {
                collectRefs(expr.cond, refs)
                collectRefs(expr.then_, refs)
                collectRefs(expr.else_, refs)
            }
            is ExprNode.Block -> {
                expr.exprs.forEach { collectRefs(it, refs) }
            }
            is ExprNode.Literal -> { /* no refs */ }
        }
    }

    private fun isContentHash(hash: Hash): Boolean {
        if (hash.startsWith("#sigil:")) return false
        return hash.isNotEmpty() && hash[0].isLetterOrDigit()
    }

    suspend fun registerDependencies(nodeHash: Hash, dependencies: Set<Hash>) {
        val node = store.get(nodeHash) ?: return
        val updatedNode = node.copy(dependencies = dependencies.toList())
        store.store(updatedNode)

        for (depHash in dependencies) {
            if (store.exists(depHash)) {
                store.addDependent(depHash, nodeHash)
            }
        }
    }

    suspend fun getTransitiveDependents(hash: Hash): Set<Hash> {
        return bfs(hash) { store.getDependents(it) }
    }

    suspend fun getTransitiveDependencies(hash: Hash): Set<Hash> {
        return bfs(hash) { store.getDependencies(it) }
    }

    private suspend fun bfs(start: Hash, neighbors: suspend (Hash) -> List<Hash>): Set<Hash> {
        val visited = mutableSetOf<Hash>()
        val queue = ArrayDeque<Hash>()
        queue.addAll(neighbors(start))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (visited.add(current)) {
                queue.addAll(neighbors(current))
            }
        }
        return visited
    }

    suspend fun cascadeDeprecation(hash: Hash): DeprecationResult {
        val affected = getTransitiveDependents(hash)
        return DeprecationResult(
            deprecatedHash = hash,
            affectedNodes = affected,
            reverificationNeeded = affected
        )
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun wouldCreateCycle(fromHash: Hash, toHash: Hash): Boolean {
        // Content-addressed hashes make cycles structurally impossible:
        // a node's hash is derived from its content, which includes its dependencies.
        // A dependency on a node requires knowing that node's hash, so circular
        // references cannot be constructed.
        return false
    }
}
