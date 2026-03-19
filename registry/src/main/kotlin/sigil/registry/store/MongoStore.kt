package sigil.registry.store

import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.client.model.Updates
import com.mongodb.kotlin.client.coroutine.MongoCollection
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import org.bson.Document
import sigil.ast.Hash
import sigil.ast.VerificationRecord

class MongoStore(
    private val collection: MongoCollection<Document>
) : RegistryStore {

    private val json = Json { ignoreUnknownKeys = true }

    private fun RegistryNode.toDocument(): Document {
        val serialized = json.encodeToString(RegistryNode.serializer(), this)
        val doc = Document.parse(serialized)
        doc["_id"] = this.hash
        return doc
    }

    private fun Document.toRegistryNode(): RegistryNode {
        val copy = Document(this)
        copy.remove("_id")
        return json.decodeFromString(RegistryNode.serializer(), copy.toJson())
    }

    override suspend fun store(node: RegistryNode): Hash {
        val doc = node.toDocument()
        collection.replaceOne(
            Filters.eq("_id", node.hash),
            doc,
            ReplaceOptions().upsert(true)
        )
        return node.hash
    }

    override suspend fun get(hash: Hash): RegistryNode? {
        val doc = collection.find(Filters.eq("_id", hash)).firstOrNull()
        return doc?.toRegistryNode()
    }

    override suspend fun exists(hash: Hash): Boolean {
        return collection.find(Filters.eq("_id", hash)).firstOrNull() != null
    }

    override suspend fun delete(hash: Hash): Boolean {
        val result = collection.deleteOne(Filters.eq("_id", hash))
        return result.deletedCount > 0
    }

    override suspend fun listAll(limit: Int, offset: Int): List<RegistryNode> {
        return collection.find()
            .skip(offset)
            .limit(limit)
            .toList()
            .map { it.toRegistryNode() }
    }

    override suspend fun getByNodeType(nodeType: String, limit: Int): List<RegistryNode> {
        return collection.find(Filters.eq("nodeType", nodeType))
            .limit(limit)
            .toList()
            .map { it.toRegistryNode() }
    }

    override suspend fun updateVerification(hash: Hash, record: VerificationRecord) {
        val serialized = json.encodeToString(VerificationRecord.serializer(), record)
        val recordDoc = Document.parse(serialized)
        collection.updateOne(
            Filters.eq("_id", hash),
            Updates.push("verification", recordDoc)
        )
    }

    override suspend fun addDependent(hash: Hash, dependentHash: Hash) {
        collection.updateOne(
            Filters.eq("_id", hash),
            Updates.addToSet("dependents", dependentHash)
        )
    }

    override suspend fun getDependents(hash: Hash): List<Hash> {
        val doc = collection.find(Filters.eq("_id", hash)).firstOrNull() ?: return emptyList()
        return doc.getList("dependents", String::class.java) ?: emptyList()
    }

    override suspend fun getDependencies(hash: Hash): List<Hash> {
        val doc = collection.find(Filters.eq("_id", hash)).firstOrNull() ?: return emptyList()
        return doc.getList("dependencies", String::class.java) ?: emptyList()
    }
}
