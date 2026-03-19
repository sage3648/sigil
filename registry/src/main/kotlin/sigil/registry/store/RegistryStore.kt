package sigil.registry.store

import sigil.ast.Hash
import sigil.ast.VerificationRecord

interface RegistryStore {
    suspend fun store(node: RegistryNode): Hash
    suspend fun get(hash: Hash): RegistryNode?
    suspend fun exists(hash: Hash): Boolean
    suspend fun delete(hash: Hash): Boolean
    suspend fun listAll(limit: Int = 100, offset: Int = 0): List<RegistryNode>
    suspend fun getByNodeType(nodeType: String, limit: Int = 100): List<RegistryNode>
    suspend fun updateVerification(hash: Hash, record: VerificationRecord)
    suspend fun addDependent(hash: Hash, dependentHash: Hash)
    suspend fun getDependents(hash: Hash): List<Hash>
    suspend fun getDependencies(hash: Hash): List<Hash>
}
