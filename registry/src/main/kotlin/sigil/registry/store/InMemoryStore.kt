package sigil.registry.store

import sigil.ast.Hash
import sigil.ast.VerificationRecord
import java.util.concurrent.ConcurrentHashMap

class InMemoryStore : RegistryStore {
    private val nodes = ConcurrentHashMap<Hash, RegistryNode>()

    override suspend fun store(node: RegistryNode): Hash {
        nodes[node.hash] = node
        return node.hash
    }

    override suspend fun get(hash: Hash): RegistryNode? = nodes[hash]

    override suspend fun exists(hash: Hash): Boolean = nodes.containsKey(hash)

    override suspend fun delete(hash: Hash): Boolean = nodes.remove(hash) != null

    override suspend fun listAll(limit: Int, offset: Int): List<RegistryNode> =
        nodes.values.drop(offset).take(limit)

    override suspend fun getByNodeType(nodeType: String, limit: Int): List<RegistryNode> =
        nodes.values.filter { it.nodeType == nodeType }.take(limit)

    override suspend fun updateVerification(hash: Hash, record: VerificationRecord) {
        val node = nodes[hash] ?: throw NoSuchElementException("Node not found: $hash")
        nodes[hash] = node.copy(verification = node.verification + record)
    }

    override suspend fun addDependent(hash: Hash, dependentHash: Hash) {
        val node = nodes[hash] ?: throw NoSuchElementException("Node not found: $hash")
        if (dependentHash !in node.dependents) {
            nodes[hash] = node.copy(dependents = node.dependents + dependentHash)
        }
    }

    override suspend fun getDependents(hash: Hash): List<Hash> =
        nodes[hash]?.dependents ?: emptyList()

    override suspend fun getDependencies(hash: Hash): List<Hash> =
        nodes[hash]?.dependencies ?: emptyList()
}
